package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisArithmeticException
import com.eignex.klause.simplex.basis.BasisOperationWork
import com.eignex.klause.simplex.basis.BasisPhaseWork
import com.eignex.klause.simplex.basis.BasisRepair
import com.eignex.klause.simplex.basis.BasisRepairControl
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.BasisUpdate
import com.eignex.klause.simplex.basis.IndexedVector
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactContinuationMetrics
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpScopedSolverTest {
    @Test
    fun `failed adoption after a solve does not repeat its pivot charge`() {
        val state = LpExactState(lowerBoundModel())
        var reject = false
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
                    override fun adopt(state: LpExactState, token: Cancellation): Boolean =
                        !reject && delegate.adopt(state, token)
                }
            }
        }
        LpScopedSolver(state, context = LpSolveContext(engineFactory = factory)).use { owner ->
            owner.withWorkingModel(LpWorkingModel.overrides(state)) { scope ->
                assertNotNull(scope.solveFloat())
                val completed = scope.metrics.solves
                assertTrue(completed.pivots > 0)
                reject = true

                assertNull(scope.solveFloat())

                assertEquals(completed, scope.metrics.solves)
                assertTrue(scope.metrics.owners.preparationWork > 0L)
            }
            assertEquals(0L, assertNotNull(owner.lastWorkingMetrics).owners.currentOwners)
            owner.requireAvailable()
        }
    }

    @Test
    fun `failed adoption after preparation charges only preparation`() {
        val state = LpExactState(lowerBoundModel())
        var adoptions = 0
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
                    override fun adopt(state: LpExactState, token: Cancellation): Boolean =
                        ++adoptions == 1 && delegate.adopt(state, token)
                }
            }
        }
        LpScopedSolver(state, context = LpSolveContext(engineFactory = factory)).use { owner ->
            owner.withWorkingModel(LpWorkingModel.overrides(state)) { scope ->
                assertNull(scope.solveFloat())

                assertEquals(2, adoptions)
                assertEquals(LpSolveMetrics(), scope.metrics.solves)
                assertTrue(scope.metrics.owners.preparationWork > 0L)
            }
            assertEquals(0L, assertNotNull(owner.lastWorkingMetrics).owners.currentOwners)
        }
    }

    @Test
    fun `engine failure after a pivot retains completed solve charge`() {
        val state = LpExactState(lowerBoundModel())
        val failure = IllegalStateException("basis update failed")
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver = RevisedSimplex(
                model,
                cancellation,
                refactorUpdateLimit,
                iterationLimit,
                workLimit,
                trackDegeneracy,
                basisSolverFactory = { matrix ->
                    val delegate = KotlinBasisSolver(matrix)
                    object : BasisSolver by delegate {
                        override fun update(
                            pivotRow: Int,
                            entering: Int,
                            spike: IndexedVector,
                            pivotEta: IndexedVector?,
                        ): BasisUpdate = throw failure
                    }
                },
                pricing = pricing,
            )
        }
        LpScopedSolver(state, context = LpSolveContext(engineFactory = factory)).use { owner ->
            owner.withWorkingModel(LpWorkingModel.overrides(state)) { scope ->
                assertSame(failure, assertFailsWith<IllegalStateException> { scope.solveFloat() })

                assertEquals(1, scope.metrics.solves.pivots)
                assertTrue(scope.metrics.solves.workOps > 0L)
            }
            assertEquals(0L, assertNotNull(owner.lastWorkingMetrics).owners.currentOwners)
            owner.requireAvailable()
        }
    }

    @Test
    fun `fresh replacement keeps partial units and records unknown work`() {
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
                    override val basisLifecycleWork = BasisOperationWork(
                        refactorization = BasisPhaseWork(units = 7),
                        complete = false,
                    )
                }
            }
        }
        LpScopedSolver(
            LpExactState(lowerBoundModel()),
            context = LpSolveContext(engineFactory = factory),
        ).use { solver ->
            assertNotNull(solver.solve())

            assertTrue(solver.append(lowerRow(1, 2), scoped = false))

            assertTrue(solver.metrics.appendBasisWork >= 7)
            assertEquals(1, solver.metrics.appendUnknownWork)
        }
    }

    @Test
    fun `scoped aggregate saturation records unknown work`() {
        val contribution = Long.MAX_VALUE / 2 + 1
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
                    override val basisLifecycleWork = BasisOperationWork(
                        refactorization = BasisPhaseWork(units = contribution),
                        complete = true,
                    )
                }
            }
        }
        LpScopedSolver(
            LpExactState(lowerBoundModel()),
            context = LpSolveContext(engineFactory = factory),
        ).use { solver ->
            assertNotNull(solver.solve())

            assertTrue(solver.append(lowerRow(1, 2), scoped = false))
            assertTrue(solver.append(lowerRow(2, 3), scoped = false))

            assertEquals(Long.MAX_VALUE, solver.metrics.appendBasisWork)
            assertTrue(solver.metrics.appendUnknownWork > 0)
        }
    }

    @Test
    fun `working scopes and bound pops restore the source objective`() {
        val x = 0
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val state = LpExactState(
            ExactLpModel(
                listOf(listOf(ExactLpEntry(0, one))),
                listOf(ExactLpNumber.of(2L)),
                listOf(
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(3L)))),
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
                ),
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(one, zero)),
            ),
        )
        LpScopedSolver(state).use { owner ->
            repeat(3) {
                assertEquals(BigFraction.ZERO, assertNotNull(owner.solve()).lowerBound)
                assertTrue(owner.push())
                assertTrue(owner.assertBound(x, false, ExactLpSide(one), 42L))
                assertEquals(BigFraction.ONE, assertNotNull(owner.solve()).lowerBound)
                val temporary = LpWorkingModel.overrides(
                    owner.state,
                    ExactLpObjective(listOf(ExactLpNumber.of(-1L), zero)),
                )
                owner.withWorkingModel(temporary) { scope ->
                    val result = assertNotNull(scope.solve())
                    assertEquals(BigFraction.ofLong(-2L), result.lowerBound)
                    assertEquals(listOf(BigFraction.ofLong(2L)), result.exactPrimal)
                }
                assertEquals(BigFraction.ONE, assertNotNull(owner.solve()).lowerBound)
                assertTrue(owner.pop(0))
            }
            assertEquals(BigFraction.ZERO, assertNotNull(owner.solve()).lowerBound)
        }
    }

    @Test
    fun `production default and compaction retain fresh replacement behavior`() {
        val source = lowerBoundModel()
        LpScopedSolver(LpExactState(source)).use { solver ->
            assertNotNull(solver.solve())
            assertTrue(solver.push())
            assertTrue(solver.append(lowerRow(1, 2), scoped = true))
            assertTrue(solver.pop(0))

            assertTrue(solver.compact())
            val settledWork = solver.metrics.appendBasisWork

            assertEquals(1, solver.state.model.m)
            B5bIndependentExactSourceValidator.validate(solver.state, assertNotNull(solver.solve()))
            assertEquals(settledWork, solver.metrics.appendBasisWork)
            assertEquals(0, solver.metrics.appendUnknownWork)
        }
    }

    @Test
    fun `cancellation after fresh preparation retains rejected owner work`() {
        var owners = 0
        var cancelled = false
        val token = Cancellation { cancelled }
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
                owners++
                val replacement = owners > 1
                val delegate = RevisedSimplex(model, cancellation, pricing = pricing)
                return object : PersistentLpSolver by delegate {
                    override val basisLifecycleWork: BasisOperationWork
                        get() {
                            if (replacement) cancelled = true
                            return checkNotNull(delegate.basisLifecycleWork)
                        }
                }
            }
        }
        val solver = LpScopedSolver(
            LpExactState(lowerBoundModel()),
            cancellation = token,
            context = LpSolveContext(engineFactory = factory),
        )
        val initial = assertNotNull(solver.solve())
        val state = solver.state

        assertTrue(!solver.append(lowerRow(1, 2), scoped = false))

        assertTrue(cancelled)
        assertTrue(solver.metrics.appendBasisWork > 0)
        assertEquals(0, solver.metrics.appendUnknownWork)
        assertTrue(solver.state === state)
        assertTrue(solver.lastResult === initial)
        assertEquals(1, solver.metrics.currentOwners)
        assertEquals(1, solver.metrics.closedOwners)
        cancelled = false
        B5bIndependentExactSourceValidator.validate(solver.state, assertNotNull(solver.solve()))
        solver.close()
    }

    @Test
    fun `replacement cleanup is suppressed behind the primary cancellation failure`() {
        val primary = IllegalStateException("primary")
        val cleanup = IllegalArgumentException("cleanup")
        var basisFactoryCalls = 0
        var targetConstructed = false
        val token = Cancellation {
            if (targetConstructed) throw primary
            false
        }
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver = RevisedSimplex(
                model,
                cancellation,
                pricing = pricing,
                basisSolverFactory = { matrix ->
                    basisFactoryCalls++
                    val target = basisFactoryCalls > 1
                    val delegate = KotlinBasisSolver(matrix)
                    if (target) targetConstructed = true
                    object : BasisSolver by delegate {
                        override fun close() {
                            delegate.close()
                            if (target) throw cleanup
                        }
                    }
                },
            )
        }
        val solver = LpScopedSolver(
            LpExactState(lowerBoundModel()),
            cancellation = token,
            context = LpSolveContext(engineFactory = factory),
        )
        assertNotNull(solver.solve())

        val thrown = assertFailsWith<IllegalStateException> {
            solver.append(lowerRow(1, 2), scoped = false)
        }

        assertTrue(thrown === primary)
        assertEquals(listOf(cleanup), thrown.suppressedExceptions.toList())
        assertTrue(solver.metrics.appendBasisWork > 0)
        assertEquals(0, solver.metrics.appendUnknownWork)
        targetConstructed = false
        solver.close()
    }

    @Test
    fun `fresh construction cleanup is suppressed behind its primary failure`() {
        val primary = IllegalStateException("primary")
        val cleanup = IllegalArgumentException("cleanup")
        var basisFactoryCalls = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver = RevisedSimplex(
                model,
                cancellation,
                pricing = pricing,
                basisSolverFactory = { matrix ->
                    basisFactoryCalls++
                    val target = basisFactoryCalls > 1
                    val delegate = KotlinBasisSolver(matrix)
                    object : BasisSolver by delegate {
                        override val basisOperationWork: BasisOperationWork
                            get() = if (target) {
                                BasisOperationWork(
                                    refactorization = BasisPhaseWork(attempts = 1, units = 7, declines = 1),
                                    complete = false,
                                )
                            } else {
                                delegate.basisOperationWork
                            }

                        override fun refactorize(basicIndex: IntArray): Boolean {
                            if (target) throw primary
                            return delegate.refactorize(basicIndex)
                        }

                        override fun close() {
                            delegate.close()
                            if (target) throw cleanup
                        }
                    }
                },
            )
        }
        val solver = LpScopedSolver(
            LpExactState(lowerBoundModel()),
            context = LpSolveContext(engineFactory = factory),
        )
        assertNotNull(solver.solve())

        val thrown = assertFailsWith<IllegalStateException> {
            solver.append(lowerRow(1, 2), scoped = false)
        }

        assertTrue(thrown === primary)
        assertEquals(listOf(cleanup), thrown.suppressedExceptions.toList())
        assertEquals(7, solver.metrics.appendBasisWork)
        assertEquals(1, solver.metrics.appendUnknownWork)
        solver.close()
    }

    @Test
    fun `disposed basis owner retains completed and failed solve work`() {
        var inject = false
        var unitsAtClose = 0L
        var owners = 0
        val working = assertNotNull(LpExactState(lowerBoundModel()).toWorkingModel())
        val engine = RevisedSimplex(working, basisSolverFactory = { matrix ->
            owners++
            val unmetered = owners > 1
            val delegate = KotlinBasisSolver(matrix)
            object : BasisSolver by delegate {
                override val basisOperationWork: BasisOperationWork?
                    get() = if (unmetered) null else delegate.basisOperationWork

                override fun ftran(x: IndexedVector, expectedDensity: Double) {
                    delegate.ftran(x, expectedDensity)
                    if (inject) throw BasisArithmeticException("after known work")
                }

                override fun close() {
                    unitsAtClose = delegate.basisOperationWork.units
                    delegate.close()
                }
            }
        })
        assertNotNull(engine.prepareLogicals(Cancellation.Never))
        val before = assertNotNull(engine.basisLifecycleWork).units
        inject = true

        assertEquals(null, engine.resolveBounds())

        val retained = assertNotNull(engine.basisLifecycleWork)
        assertTrue(retained.units > before)
        assertEquals(unitsAtClose, retained.units)
        assertTrue(retained.complete)
        inject = false
        assertNotNull(engine.resolveBounds())
        val recreated = assertNotNull(engine.basisLifecycleWork)
        assertTrue(recreated.units >= retained.units)
        assertTrue(!recreated.complete)
        engine.close()
    }

    @Test
    fun `fresh arithmetic decline propagates cleanup failure`() {
        val cleanup = IllegalStateException("cleanup")
        var basisFactoryCalls = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver = RevisedSimplex(
                model,
                cancellation,
                pricing = pricing,
                basisSolverFactory = { matrix ->
                    basisFactoryCalls++
                    val target = basisFactoryCalls > 1
                    val delegate = KotlinBasisSolver(matrix)
                    object : BasisSolver by delegate {
                        override val basisOperationWork: BasisOperationWork
                            get() = if (target) {
                                BasisOperationWork(
                                    refactorization = BasisPhaseWork(attempts = 1, units = 7, declines = 1),
                                )
                            } else {
                                delegate.basisOperationWork
                            }

                        override fun refactorize(basicIndex: IntArray): Boolean {
                            if (target) throw BasisArithmeticException("arithmetic")
                            return delegate.refactorize(basicIndex)
                        }

                        override fun refactorizeRepairing(
                            basicIndex: IntArray,
                            control: BasisRepairControl,
                        ): BasisRepair? = if (target) null else delegate.refactorizeRepairing(basicIndex, control)

                        override fun close() {
                            delegate.close()
                            if (target) throw cleanup
                        }
                    }
                },
            )
        }
        val solver = LpScopedSolver(
            LpExactState(lowerBoundModel()),
            context = LpSolveContext(engineFactory = factory),
        )
        assertNotNull(solver.solve())

        val thrown = assertFailsWith<IllegalStateException> {
            solver.append(lowerRow(1, 2), scoped = false)
        }

        assertTrue(thrown === cleanup)
        assertEquals(7, solver.metrics.appendBasisWork)
        assertEquals(0, solver.metrics.appendUnknownWork)
        solver.close()
    }

    @Test
    fun `fresh telemetry failure closes the constructed basis owner`() {
        val telemetry = IllegalStateException("telemetry")
        val cleanup = IllegalArgumentException("cleanup")
        var basisFactoryCalls = 0
        var targetCloses = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver = RevisedSimplex(
                model,
                cancellation,
                pricing = pricing,
                basisSolverFactory = { matrix ->
                    basisFactoryCalls++
                    val target = basisFactoryCalls > 1
                    val delegate = KotlinBasisSolver(matrix)
                    object : BasisSolver by delegate {
                        override val basisOperationWork: BasisOperationWork
                            get() = if (target) throw telemetry else delegate.basisOperationWork

                        override fun close() {
                            if (target) targetCloses++
                            delegate.close()
                            if (target) throw cleanup
                        }
                    }
                },
            )
        }
        val solver = LpScopedSolver(
            LpExactState(lowerBoundModel()),
            context = LpSolveContext(engineFactory = factory),
        )
        val initial = assertNotNull(solver.solve())
        val state = solver.state

        val thrown = assertFailsWith<IllegalStateException> {
            solver.append(lowerRow(1, 2), scoped = false)
        }

        assertTrue(thrown === telemetry)
        assertEquals(listOf(cleanup), thrown.suppressedExceptions.toList())
        assertEquals(1, targetCloses)
        assertTrue(solver.state === state)
        assertTrue(solver.lastResult === initial)
        B5bIndependentExactSourceValidator.validate(solver.state, assertNotNull(solver.solve()))
        solver.close()
    }

    @Test
    fun `telemetry failure before publication closes replacement and preserves old owner`() {
        val telemetry = IllegalStateException("telemetry")
        var owners = 0
        var closes = 0
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
                owners++
                val replacement = owners > 1
                val delegate = RevisedSimplex(model, cancellation, pricing = pricing)
                return object : PersistentLpSolver by delegate {
                    override val basisLifecycleWork: BasisOperationWork
                        get() = if (replacement) throw telemetry else checkNotNull(delegate.basisLifecycleWork)

                    override fun close() {
                        closes++
                        delegate.close()
                    }
                }
            }
        }
        val solver = LpScopedSolver(
            LpExactState(lowerBoundModel()),
            context = LpSolveContext(engineFactory = factory),
        )
        val initial = assertNotNull(solver.solve())
        val state = solver.state

        val thrown = assertFailsWith<IllegalStateException> {
            solver.append(lowerRow(1, 2), scoped = false)
        }

        assertTrue(thrown === telemetry)
        assertTrue(solver.state === state)
        assertTrue(solver.lastResult === initial)
        assertEquals(1, closes)
        assertEquals(1, solver.metrics.appendUnknownWork)
        B5bIndependentExactSourceValidator.validate(solver.state, assertNotNull(solver.solve()))
        solver.close()
        assertEquals(2, closes)
    }

    @Test
    fun `pending telemetry is suppressed behind a post append solve failure`() {
        val primary = IllegalStateException("primary")
        val telemetry = IllegalArgumentException("telemetry")
        var owners = 0
        var replacementLedgerReads = 0
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
                owners++
                val replacement = owners > 1
                val delegate = RevisedSimplex(model, cancellation, pricing = pricing)
                return object : PersistentLpSolver by delegate {
                    override val basisLifecycleWork: BasisOperationWork
                        get() {
                            if (replacement && replacementLedgerReads++ > 0) throw telemetry
                            return checkNotNull(delegate.basisLifecycleWork)
                        }

                    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? {
                        if (replacement) throw primary
                        return delegate.resolveBounds(allowance)
                    }
                }
            }
        }
        val solver = LpScopedSolver(
            LpExactState(lowerBoundModel()),
            context = LpSolveContext(engineFactory = factory),
        )
        assertNotNull(solver.solve())
        assertTrue(solver.append(lowerRow(1, 2), scoped = false))

        val thrown = assertFailsWith<IllegalStateException> { solver.solve() }

        assertTrue(thrown === primary)
        assertEquals(listOf(telemetry), thrown.suppressedExceptions.toList())
        assertEquals(1, solver.metrics.appendUnknownWork)
        solver.close()
    }

    @Test
    fun `rejected candidate preserves primary failure over telemetry and cleanup`() {
        val primary = IllegalStateException("primary")
        val telemetry = IllegalArgumentException("telemetry")
        val cleanup = UnsupportedOperationException("cleanup")
        var owners = 0
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
                owners++
                val replacement = owners > 1
                val delegate = RevisedSimplex(model, cancellation, pricing = pricing)
                return object : PersistentLpSolver by delegate {
                    override val basisLifecycleWork: BasisOperationWork
                        get() = if (replacement) throw telemetry else checkNotNull(delegate.basisLifecycleWork)

                    override fun adopt(state: LpExactState, token: Cancellation): Boolean {
                        if (replacement) throw primary
                        return delegate.adopt(state, token)
                    }

                    override fun close() {
                        delegate.close()
                        if (replacement) throw cleanup
                    }
                }
            }
        }
        val solver = LpScopedSolver(
            LpExactState(lowerBoundModel()),
            context = LpSolveContext(engineFactory = factory),
        )
        val initial = assertNotNull(solver.solve())
        val state = solver.state

        val thrown = assertFailsWith<IllegalStateException> {
            solver.append(lowerRow(1, 2), scoped = false)
        }

        assertTrue(thrown === primary)
        assertEquals(listOf(telemetry, cleanup), thrown.suppressedExceptions.toList())
        assertEquals(1, solver.metrics.appendUnknownWork)
        assertTrue(solver.state === state)
        assertTrue(solver.lastResult === initial)
        B5bIndependentExactSourceValidator.validate(solver.state, assertNotNull(solver.solve()))
        solver.close()
    }

    @Test
    fun `exact source validation uses shifted coordinates logical costs and minimized scale`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(-1L)))),
            listOf(ExactLpNumber.of(-1L)),
            listOf(
                ExactLpColumn(
                    ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L))),
                    origin = ExactLpNumber.of(1L),
                ),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(
                listOf(ExactLpNumber.of(2L), ExactLpNumber.of(3L)),
                constant = ExactLpNumber.of(4L),
                scale = ExactLpNumber.of(2L),
                externalConstant = ExactLpNumber.of(5L),
            ),
        )
        LpScopedSolver(LpExactState(model)).use { solver ->
            val result = assertNotNull(solver.solve())

            B5bIndependentExactSourceValidator.validate(solver.state, result)

            assertEquals(listOf(BigFraction.ofLong(2L)), result.exactPrimal)
            assertEquals(BigFraction.ofLong(8L), assertNotNull(result.witness).objective)
        }
    }

    private fun lowerBoundModel(): ExactLpModel {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val minusOne = ExactLpNumber.of(-1L)
        return ExactLpModel(
            listOf(listOf(ExactLpEntry(0, minusOne))),
            listOf(minusOne),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(one, zero)),
        )
    }

    private fun lowerRow(id: Long, lower: Long): LpScopedRow = LpScopedRow(
        id,
        listOf(0 to ExactLpNumber.of(-1L)),
        ExactLpNumber.of(-lower),
        ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L)))),
    )

    @Test
    fun `cancelled publication retains continuation cost without exposing its witness`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(1L)))),
            listOf(ExactLpNumber.of(1L)),
            listOf(ExactLpColumn(ExactLpBounds()), ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)))),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, zero)),
        )
        var cancelled = false
        var observed: ExactContinuationMetrics? = null
        val observer = object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean) = Unit
            override fun observeExactInput(accepted: Boolean) = Unit
            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
            override fun observeContinuation(metrics: ExactContinuationMetrics) {
                observed = metrics
                cancelled = true
            }
        }
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
                    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? =
                        delegate.resolveBounds(LpFloatAllowance(1L, 1024))
                }
            }
        }
        LpScopedSolver(
            LpExactState(source),
            Cancellation { cancelled },
            LpSolveContext(factory),
            workLimit = 10_000L,
        ).use { owner ->
            val result = owner.solve(observer = observer)

            assertNull(result)
            assertNull(owner.lastResult)
            assertTrue(assertNotNull(observed).success)
            assertTrue(observed.work > 0L)
        }
    }

    @Test
    fun `rejected preparation preserves the primary failure when cleanup also throws`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)))),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        val primary = IllegalStateException("preparation failure")
        val cleanup = IllegalStateException("cleanup failure")
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
                    override fun prepareLogicals(token: Cancellation): Basis? =
                        if (model.m > 0) throw primary else delegate.prepareLogicals(token)

                    override fun close() {
                        delegate.close()
                        if (model.m > 0) throw cleanup
                    }
                }
            }
        }
        LpScopedSolver(LpExactState(source), context = LpSolveContext(engineFactory = factory)).use { solver ->
            val original = assertNotNull(solver.solve())
            val before = solver.state
            val row = LpScopedRow(1, listOf(0 to one), one, ExactLpColumn(ExactLpBounds()))

            val failure = assertFailsWith<IllegalStateException> { solver.append(row, false) }

            assertSame(primary, failure)
            assertSame(cleanup, failure.suppressedExceptions.single())
            assertSame(before, solver.state)
            assertSame(original, solver.lastResult)
            assertEquals(1L, solver.metrics.editDeclines)
            assertEquals(1L, solver.metrics.closedOwners)
            assertEquals(1L, solver.metrics.currentOwners)
        }
    }

    @Test
    fun `published replacement stays accepted and clears solve metrics when old owner cleanup throws`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val minusOne = ExactLpNumber.of(-1L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, minusOne))),
            listOf(minusOne),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(2L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(one, zero)),
        )
        val cleanup = IllegalStateException("old owner cleanup failure")
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
                    override fun close() {
                        delegate.close()
                        if (model.m == 1) throw cleanup
                    }
                }
            }
        }
        LpScopedSolver(LpExactState(source), context = LpSolveContext(engineFactory = factory)).use { solver ->
            assertNotNull(solver.solve())
            assertTrue(solver.lastMetrics.workOps > 0L)
            val row = LpScopedRow(2, listOf(0 to one), one, ExactLpColumn(ExactLpBounds()))

            val failure = assertFailsWith<IllegalStateException> { solver.append(row, false) }

            assertSame(cleanup, failure)
            assertEquals(listOf(0L, 2L), solver.state.rows.entries().map { it.id })
            assertNull(solver.lastResult)
            assertEquals(LpSolveMetrics(), solver.lastMetrics)
            assertEquals(1L, solver.metrics.editSuccesses)
            assertEquals(0L, solver.metrics.editDeclines)
            assertEquals(1L, solver.metrics.closedOwners)
            assertEquals(1L, solver.metrics.currentOwners)
            assertEquals(BigFraction.ONE, assertNotNull(solver.solve()).lowerBound)
        }
    }

    @Test
    fun `appended row conflict cancels structural coefficients and preserves guarded proof after pop`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val minusOne = ExactLpNumber.of(-1L)
        val logical = ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        LpScopedSolver(LpExactState(source)).use { solver ->
            assertTrue(
                solver.append(
                    LpScopedRow(
                        2,
                        listOf(0 to one),
                        zero,
                        logical,
                        ExactLpRow(false, premises = ExactLpPremises(emptyList(), listOf(41))),
                    ),
                    false,
                ),
            )
            assertTrue(solver.push())
            assertTrue(
                solver.append(
                    LpScopedRow(
                        3,
                        listOf(0 to minusOne),
                        minusOne,
                        logical,
                        ExactLpRow(false, premises = ExactLpPremises(emptyList(), listOf(42))),
                    ),
                    true,
                ),
            )

            val result = assertNotNull(solver.solve())

            assertEquals(LpVerdict.INFEASIBLE, result.verdict)
            assertNull(result.boundConflict)
            val proof = assertNotNull(result.rationalConflict)
            val support = assertNotNull(result.conflictSupport)
            assertEquals(setOf(0, 1), proof.rows.toSet())
            var coefficient = BigFraction.ZERO
            var rhs = BigFraction.ZERO
            for (entry in proof.rows.indices) {
                val row = proof.rows[entry]
                val multiplier = proof.multipliers[entry]
                assertTrue(multiplier > BigFraction.ZERO)
                coefficient += multiplier * if (row == 0) BigFraction.ONE else BigFraction.ONE.negated()
                rhs += multiplier * if (row == 0) BigFraction.ZERO else BigFraction.ONE.negated()
            }
            assertEquals(BigFraction.ZERO, coefficient)
            assertTrue(rhs < BigFraction.ZERO)
            assertEquals(setOf(1, 2), proof.bounds.map { it.column }.toSet())
            assertTrue(proof.bounds.all { !it.upper })
            assertEquals(
                setOf(41, 42),
                support.rows.map { assertNotNull(it.second.premises).literalEntries().single() }.toSet(),
            )

            assertTrue(solver.pop(0))
            assertTrue(solver.compact())

            val feasible = assertNotNull(solver.solve())
            assertEquals(LpVerdict.ATTAINED_OPTIMUM, feasible.verdict)
            assertTrue(assertNotNull(feasible.exactPrimal).single() <= BigFraction.ZERO)
            assertEquals(listOf(2L), solver.state.rows.entries().map { it.id })
            assertEquals(listOf(2L, 3L), support.state.rows.entries().map { it.id })
            assertTrue(checkedLpConflict(assertNotNull(support.state.toWorkingModel()), proof))
        }
    }

    @Test
    fun `compaction preserves a priced rational logical and IEEE row in source objective units`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val third = ExactLpNumber.of(BigFraction.ofLong(3L).reciprocal())
        val half = ExactLpNumber.ofIeee(0.5)
        val logical = ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))
        val column = ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)), origin = one)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(column),
            emptyList(),
            ExactLpObjective(listOf(zero), third, third, third),
        )
        LpScopedSolver(LpExactState(source)).use { solver ->
            assertTrue(solver.push())
            assertTrue(solver.append(LpScopedRow(1, listOf(0 to one), one, logical), true))
            assertTrue(solver.append(LpScopedRow(2, listOf(0 to third), third, logical, cost = third), false))
            assertTrue(solver.append(LpScopedRow(3, listOf(0 to half), half, logical), false))
            val before = assertNotNull(solver.solve())

            assertTrue(solver.pop(0))
            assertTrue(solver.compact())
            val result = assertNotNull(solver.solve())

            assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
            assertEquals(listOf(BigFraction.ofLong(2L)), result.exactPrimal)
            assertEquals(BigFraction.ONE + third.value, result.lowerBound)
            assertEquals(before.lowerBound, result.lowerBound)
            assertEquals(third, solver.state.model.objective.cost(1))
            assertEquals(half, solver.state.model.entries(0)[1].number)
            assertEquals(half, solver.state.model.rhs(1))
            val fresh = ExactLpModel(
                listOf(listOf(ExactLpEntry(0, third), ExactLpEntry(1, half))),
                listOf(third, half),
                listOf(column, logical, logical),
                List(2) { ExactLpRow() },
                ExactLpObjective(listOf(zero, third, zero), third, third, third),
            )
            val independent = solveAndCertify(fresh)
            assertEquals(independent.exactPrimal, result.exactPrimal)
            assertEquals(independent.lowerBound, result.lowerBound)
        }
    }

    @Test
    fun `local nonbasic logical pops and compacts with bounded retained state and exact optima`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val minusOne = ExactLpNumber.of(-1L)
        val logical = ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, minusOne))),
            listOf(minusOne),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L)))), logical),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(one, zero)),
        )
        val solver = LpScopedSolver(LpExactState(source))
        var solveAttempts = 0
        var solves = 0
        val counters = LpCounterResults()
        solver.use {
            repeat(12) { cycle ->
                val lower = 2L + cycle % 3
                val premises = ExactLpPremises(emptyList(), listOf(101 + cycle))
                assertTrue(solver.push())
                assertTrue(
                    solver.append(
                        LpScopedRow(
                            cycle + 1L,
                            listOf(0 to minusOne),
                            ExactLpNumber.of(-lower),
                            logical,
                            ExactLpRow(false, premises = premises),
                        ),
                        scoped = true,
                    ),
                )
                solveAttempts++
                val local = assertNotNull(solver.solve(counterResults = counters))
                solves++
                val point = assertNotNull(local.witness).primal.single()
                assertEquals(BigFraction.ofLong(lower), point)
                assertEquals(point, local.lowerBound)
                assertEquals(LpVerdict.ATTAINED_OPTIMUM, local.verdict)
                assertFalse(assertNotNull(local.float).basis.status[2] == VarStatus.BASIC)
                val support = assertNotNull(local.bound?.support)
                assertEquals(cycle + 1L, support.state.rows.row(support.rows.single().first).id)
                assertEquals(premises, support.rows.single().second.premises)
                val duals = local.float.duals
                assertEquals(-1.0, duals[1])
                assertEquals(0.0, duals[0])
                val localKey = assertNotNull(LpExactCapture.stateKey(solver.state))

                assertTrue(solver.pop(0))
                assertNull(solver.lastResult)
                assertNull(counters.read(assertNotNull(solver.state.toWorkingModel()), ProductionLpCertificationPolicy))
                solveAttempts++
                val popped = assertNotNull(solver.solve())
                solves++
                assertEquals(BigFraction.ONE, popped.lowerBound)
                assertEquals(listOf(BigFraction.ONE), popped.exactPrimal)
                assertEquals(listOf(0), assertNotNull(popped.bound?.support).rows.map { it.first })
                assertEquals(lower.toDouble(), assertNotNull(local.safeLowerBound), 1e-9)
                assertFalse(localKey.contentEquals(assertNotNull(LpExactCapture.stateKey(solver.state))))

                assertTrue(solver.compact())
                solveAttempts++
                val compacted = assertNotNull(solver.solve())
                solves++
                assertEquals(BigFraction.ONE, compacted.lowerBound)
                assertEquals(listOf(BigFraction.ONE), compacted.exactPrimal)
                assertEquals(1, solver.metrics.retainedRows)
                assertEquals(1, solver.metrics.activeRows)
                assertEquals(1L, solver.metrics.currentOwners)
                if (cycle == 0) {
                    val fresh = solveAndCertify(source)
                    assertEquals(compacted.lowerBound, fresh.lowerBound)
                    assertEquals(compacted.exactPrimal, fresh.exactPrimal)
                    val freshLocal = ExactLpModel(
                        listOf(listOf(ExactLpEntry(0, minusOne))),
                        listOf(ExactLpNumber.of(-lower)),
                        List(source.numVars) { source.column(it) },
                        listOf(ExactLpRow(false, premises = premises)),
                        source.objective,
                    )
                    val checkedFresh = solveAndCertify(freshLocal)
                    assertEquals(local.lowerBound, checkedFresh.lowerBound)
                    assertEquals(local.exactPrimal, checkedFresh.exactPrimal)
                }
            }
            assertEquals(36, solves)
            assertEquals(solveAttempts, solves)
            assertEquals(0L, solver.metrics.editDeclines)
            assertEquals(0L, solver.metrics.preparationDeclines)
            assertEquals(36L, solver.metrics.preparationAttempts)
            assertEquals(2L, solver.metrics.peakOwners)
        }
        assertEquals(0L, solver.metrics.currentOwners)
        assertEquals(solver.metrics.createdOwners, solver.metrics.closedOwners)
        solver.close()
        assertFalse(solver.push())
        assertNull(solver.solve())
    }

    @Test
    fun `explicit deactivation of strict sides permits the exact boundary witness`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)))),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        LpScopedSolver(LpExactState(source)).use { solver ->
            assertTrue(
                solver.append(
                    LpScopedRow(
                        4,
                        listOf(0 to one),
                        one,
                        ExactLpColumn(ExactLpBounds(upper = ExactLpSide(one, strict = true))),
                    ),
                    scoped = false,
                ),
            )
            val strict = assertNotNull(solver.solve())
            val point = assertNotNull(strict.witness)
            assertTrue(point.primal.single() > BigFraction.ZERO && point.primal.single() <= BigFraction.ONE)
            assertEquals(point.primal.single(), point.objective)
            assertEquals(LpVerdict.FEASIBLE, strict.verdict)
            assertEquals(BigFraction.ZERO, strict.lowerBound)

            assertTrue(solver.deactivate(4))
            val result = assertNotNull(solver.solve())

            assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
            assertEquals(listOf(BigFraction.ZERO), result.exactPrimal)
            assertEquals(BigFraction.ZERO, result.lowerBound)
            assertTrue(assertNotNull(result.bound?.support).rows.isEmpty())
            assertEquals(0L, solver.lastMetrics.initialRefactorizations.toLong())
            assertTrue(solver.compact())
            assertEquals(result.exactPrimal, assertNotNull(solver.solve()).exactPrimal)
        }
    }

    @Test
    fun `logical objective must be explicitly removed before row lifetime ends`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val bounds = ExactLpBounds(ExactLpSide(zero), ExactLpSide(one))
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(bounds)),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        LpScopedSolver(LpExactState(source)).use { solver ->
            assertTrue(solver.push())
            assertTrue(solver.append(LpScopedRow(1, listOf(0 to one), one, ExactLpColumn(bounds), cost = one), true))
            val priced = assertNotNull(solver.solve())
            assertEquals(listOf(BigFraction.ONE), priced.exactPrimal)
            assertEquals(BigFraction.ZERO, priced.lowerBound)
            val before = solver.state
            assertFalse(solver.pop(0))
            assertSame(before, solver.state)
            assertSame(priced, solver.lastResult)

            assertTrue(solver.replaceObjective(ExactLpObjective(listOf(one, zero))))
            assertTrue(solver.pop(0))
            assertTrue(solver.compact())

            val result = assertNotNull(solver.solve())
            assertEquals(listOf(BigFraction.ZERO), result.exactPrimal)
            assertEquals(BigFraction.ZERO, result.lowerBound)
            assertEquals(1L, solver.metrics.editDeclines)
        }
    }

    @Test
    fun `conflicting append retains row guards and immutable direct support after remap recenter and pop`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val guard = ExactLpPremises(emptyList(), listOf(31))
        val lowerPremises = ExactLpPremises(listOf(ExactLpPremise(5, false, zero)))
        val upperPremises = ExactLpPremises(emptyList(), listOf(32))
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        LpScopedSolver(LpExactState(source)).use { solver ->
            assertTrue(solver.append(LpScopedRow(2, listOf(0 to one), one, ExactLpColumn(ExactLpBounds())), false))
            assertTrue(
                solver.append(
                    LpScopedRow(
                        3,
                        listOf(0 to one),
                        ExactLpNumber.of(8L),
                        ExactLpColumn(ExactLpBounds(ExactLpSide(zero, premises = lowerPremises), ExactLpSide(zero))),
                        ExactLpRow(false, true, guard),
                    ),
                    false,
                ),
            )
            val appended = assertNotNull(solver.solve())
            assertEquals(LpVerdict.INFEASIBLE, appended.verdict)
            assertTrue(assertNotNull(appended.boundConflict).lower.side.strict)
            assertEquals(listOf(1), assertNotNull(appended.conflictSupport).rows.map { it.first })
            assertTrue(solver.deactivate(2))
            assertTrue(solver.compact())
            assertTrue(solver.push())
            assertTrue(solver.assertBound(1, true, ExactLpSide(ExactLpNumber.of(-1L), premises = upperPremises), 99))
            assertTrue(solver.recenter(listOf(one)))
            val conflict = assertNotNull(solver.solve())
            val support = assertNotNull(conflict.conflictSupport)
            assertEquals(listOf(0), support.rows.map { it.first })
            assertEquals(3L, support.state.rows.row(0).id)
            assertEquals(guard, support.rows.single().second.premises)
            assertEquals(listOf(-3L, 99L), support.sides.map { it.witness })
            assertEquals(lowerPremises, support.sides[0].side.premises)
            assertEquals(upperPremises, support.sides[1].side.premises)
            assertTrue(support.sides[0].side.number.value > support.sides[1].side.number.value)

            assertTrue(solver.pop(0))
            assertTrue(solver.deactivate(3))
            assertTrue(solver.compact())
            assertTrue(solver.recenter(listOf(zero)))

            assertEquals(one, support.state.model.column(0).origin)
            assertEquals(ExactLpNumber.of(7L), support.state.model.rhs(0))
            assertEquals(1, support.state.assertions.size)
            assertEquals(0, solver.state.model.m)
        }
    }

    @Test
    fun `failed append preparation closes staged owners and preserves the current solve`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)))),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        for (failure in listOf("unsupported", "singular", "arithmetic", "unexpected", "cancel")) {
            var fail = false
            var cancelled = false
            var closes = 0
            val token = Cancellation { cancelled }
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
                    val delegate = RevisedSimplex(
                        model,
                        cancellation,
                        pricing = pricing,
                        basisSolverFactory = { matrix ->
                            val factors = KotlinBasisSolver(matrix)
                            object : BasisSolver by factors {
                                override fun refactorize(basicIndex: IntArray): Boolean {
                                    if (fail) {
                                        when (failure) {
                                            "singular" -> return false
                                            "arithmetic" -> throw BasisArithmeticException("injected preparation")
                                            "unexpected" -> error("injected preparation")
                                            "cancel" -> cancelled = true
                                        }
                                    }
                                    return factors.refactorize(basicIndex)
                                }

                                override fun refactorizeRepairing(
                                    basicIndex: IntArray,
                                    control: BasisRepairControl,
                                ): BasisRepair? {
                                    if (fail) {
                                        when (failure) {
                                            "singular" -> return null
                                            "arithmetic" -> throw BasisArithmeticException("injected preparation")
                                            "unexpected" -> error("injected preparation")
                                            "cancel" -> cancelled = true
                                        }
                                    }
                                    return factors.refactorizeRepairing(basicIndex, control)
                                }
                            }
                        },
                    )
                    return object : PersistentLpSolver by delegate {
                        override fun prepareLogicals(token: Cancellation): Basis? =
                            if (fail && failure == "unsupported") null else delegate.prepareLogicals(token)
                        override fun close() {
                            closes++
                            delegate.close()
                        }
                    }
                }
            }
            LpScopedSolver(LpExactState(source), token, LpSolveContext(engineFactory = factory)).use { solver ->
                val original = assertNotNull(solver.solve())
                val before = solver.state
                fail = true
                val row = LpScopedRow(1, listOf(0 to one), zero, ExactLpColumn(ExactLpBounds()))
                if (failure == "unexpected") {
                    assertFailsWith<IllegalStateException> { solver.append(row, true) }
                } else {
                    assertFalse(solver.append(row, true))
                }
                assertSame(before, solver.state)
                assertSame(original, solver.lastResult)
                assertEquals(1L, solver.metrics.currentOwners)
                assertEquals(1L, solver.metrics.editDeclines)
                assertEquals(1L, solver.metrics.preparationDeclines)
                assertEquals(1, closes)
                fail = false
                cancelled = false
                assertEquals(original.exactPrimal, assertNotNull(solver.solve()).exactPrimal)
            }
            assertEquals(2, closes)
        }
    }

    @Test
    fun `construction failures and cancellation after an accepted append preserve source ownership`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)))),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        var failConstruction = false
        var cancelCertificate = false
        var cancelled = false
        val token = Cancellation { cancelled }
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
                if (failConstruction) error("injected factory failure")
                return RevisedSimplex(model, cancellation, pricing = pricing)
            }
        }
        val policy = object : LpCertificationPolicy by ProductionLpCertificationPolicy {
            override fun accepts(certifier: LpCertifier, successful: Boolean): Boolean {
                if (cancelCertificate) cancelled = true
                return successful
            }
        }
        LpScopedSolver(LpExactState(source), token, LpSolveContext(factory, policy)).use { solver ->
            assertNotNull(solver.solve())
            val before = solver.state
            val row = LpScopedRow(
                2,
                listOf(0 to one),
                zero,
                ExactLpColumn(ExactLpBounds(ExactLpSide(one), ExactLpSide(zero))),
            )
            failConstruction = true
            assertFailsWith<IllegalStateException> { solver.append(row, true) }
            assertSame(before, solver.state)
            assertEquals(1L, solver.metrics.currentOwners)
            failConstruction = false
            assertTrue(solver.append(row, true))
            cancelCertificate = true

            assertNull(solver.solve())

            assertNull(solver.lastResult)
            assertEquals(2L, solver.state.rows.row(0).id)
            assertNotNull(solver.state.conflict)
            cancelled = false
            cancelCertificate = false
            val result = assertNotNull(solver.solve())
            assertEquals(LpVerdict.INFEASIBLE, result.verdict)
            assertNotNull(result.conflictSupport)
        }
    }

    @Test
    fun `failed compaction staging or reduced build preserves tombstones and the live owner`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))),
            listOf(one),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one))), ExactLpColumn(ExactLpBounds())),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(one, zero)),
        )
        for (failureOffset in listOf(1, 2)) {
            var builds = 0
            var failAt = -1
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
                        override fun prepareLogicals(token: Cancellation): Basis? =
                            if (++builds == failAt) null else delegate.prepareLogicals(token)
                    }
                }
            }
            LpScopedSolver(LpExactState(source), context = LpSolveContext(engineFactory = factory)).use { solver ->
                assertNotNull(solver.solve())
                assertTrue(solver.deactivate(0))
                val before = solver.state
                failAt = builds + failureOffset

                assertFalse(solver.compact())

                assertSame(before, solver.state)
                assertEquals(1L, solver.metrics.currentOwners)
                assertEquals(1, solver.metrics.retainedRows)
                assertEquals(0, solver.metrics.activeRows)
                assertEquals(1L, solver.metrics.preparationDeclines)
                assertEquals(listOf(BigFraction.ZERO), assertNotNull(solver.solve()).exactPrimal)
                failAt = -1
                assertTrue(solver.compact())
                assertEquals(0, solver.state.model.m)
            }
        }
    }

    @Test
    fun `resource and adoption declines are atomic and cancelled solves withhold committed assertions`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)))),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        var reject = false
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
                    override fun adopt(state: LpExactState, token: Cancellation): Boolean =
                        !reject && delegate.adopt(state, token)
                }
            }
        }
        LpScopedSolver(
            LpExactState(source),
            context = LpSolveContext(engineFactory = factory),
            maxRetainedRows = 0,
        ).use { solver ->
            val original = assertNotNull(solver.solve())
            val before = solver.state
            assertFalse(solver.append(LpScopedRow(0, emptyList(), zero, ExactLpColumn(ExactLpBounds())), false))
            assertSame(before, solver.state)
            reject = true
            assertFalse(solver.assertBound(0, false, ExactLpSide(one), 1))
            assertSame(before, solver.state)
            assertSame(original, solver.lastResult)
            reject = false
            assertTrue(solver.assertBound(0, false, ExactLpSide(one), 1))

            assertNull(solver.solve(token = Cancellation { true }))

            assertNull(solver.lastResult)
            assertEquals(1L, solver.state.assertions.single().witness)
            assertEquals(listOf(BigFraction.ONE), assertNotNull(solver.solve()).exactPrimal)
            assertEquals(2L, solver.metrics.editDeclines)
        }
    }

    @Test
    fun `ordinary source basis remains usable after a cut append`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 8L, cost = 2L)
        repeat(10) { builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 3L) }
        val source = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val solved = solveAndCertify(source.model)
        val basis = assertNotNull(solved.float).basis
        assertEquals(listOf(BigFraction.ofLong(3L)), solved.exactPrimal)

        LpScopedSolver(source).use { owner ->
            assertEquals(BigFraction.ofLong(6L), owner.solve(basis)?.lowerBound)
            assertTrue(
                owner.append(
                    LpScopedRow(
                        id = 100L,
                        coefficients = listOf(0 to ExactLpNumber.of(1L)),
                        rhs = ExactLpNumber.of(4L),
                        logical = ExactLpColumn(ExactLpBounds(upper = ExactLpSide(ExactLpNumber.of(0L)))),
                    ),
                    scoped = false,
                ),
            )
            val after = assertNotNull(owner.solve())
            assertEquals(BigFraction.ofLong(8L), after.lowerBound)
            assertEquals(listOf(BigFraction.ofLong(4L)), after.exactPrimal)
        }
    }
}

internal object B5bIndependentExactSourceValidator {
    fun validate(state: LpExactState, result: CertifiedLpResult) {
        val primal = assertNotNull(result.exactPrimal)
        assertEquals(state.model.n, primal.size)
        val shifted = List(state.model.n) { column ->
            primal[column] - state.model.column(column).origin.value
        }
        for (column in shifted.indices) validateBounds(shifted[column], state.model.column(column).bounds)
        val logicals = MutableList(state.model.m) { BigFraction.ZERO }
        for (row in 0 until state.model.m) {
            var logical = state.model.rhs(row).value
            for (column in 0 until state.model.n) {
                val coefficient = state.model.entries(column).firstOrNull { it.row == row }?.number?.value
                    ?: BigFraction.ZERO
                logical -= coefficient * shifted[column]
            }
            validateBounds(logical, state.model.column(state.model.n + row).bounds)
            logicals[row] = logical
        }
        val coordinates = shifted + logicals
        var objective = state.model.objective.constant.value
        for (column in coordinates.indices) {
            objective += state.model.objective.cost(column).value * coordinates[column]
        }
        objective = objective * state.model.objective.scale.value.reciprocal() +
            state.model.objective.externalConstant.value
        assertEquals(objective, assertNotNull(result.witness).objective)
    }

    private fun validateBounds(value: BigFraction, bounds: ExactLpBounds) {
        bounds.lower?.let { side ->
            if (side.strict) assertTrue(value > side.number.value) else assertTrue(value >= side.number.value)
        }
        bounds.upper?.let { side ->
            if (side.strict) assertTrue(value < side.number.value) else assertTrue(value <= side.number.value)
        }
    }
}
