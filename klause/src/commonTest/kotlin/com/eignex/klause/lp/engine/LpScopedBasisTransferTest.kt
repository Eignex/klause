package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisOperationWork
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpScopedBasisTransferTest {
    @Test
    fun `successive appends transfer accepted source and extension unit headings`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val minusOne = ExactLpNumber.of(-1L)
        val structural = ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L))))
        val logical = ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, minusOne))),
            listOf(minusOne),
            listOf(structural, logical),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(one, zero)),
        )
        LpScopedSolver(
            LpExactState(source),
            appendSelection = LpAppendSelection.FORCE_TRANSFER,
        ).use { solver ->
            B5bIndependentExactSourceValidator.validate(solver.state, assertNotNull(solver.solve()))

            assertTrue(solver.append(lowerRow(1, 2), scoped = true))
            B5bIndependentExactSourceValidator.validate(solver.state, assertNotNull(solver.solve()))
            assertTrue(
                solver.append(
                    LpScopedRow(
                        2,
                        listOf(0 to ExactLpNumber.ofIeee(-0.5)),
                        ExactLpNumber.ofIeee(-1.5),
                        ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
                    ),
                    scoped = false,
                ),
            )
            B5bIndependentExactSourceValidator.validate(solver.state, assertNotNull(solver.solve()))

            assertEquals(2, solver.metrics.appendReplacementAttempts)
            assertEquals(2, solver.metrics.appendTransfers)
            assertEquals(0, solver.metrics.appendFallbacks)
            assertEquals(0, solver.metrics.appendUnknownWork)
            assertTrue(solver.metrics.appendBasisWork > 0)
            assertEquals(listOf(0L, 1L, 2L), solver.state.rows.entries().map { it.id })
        }
    }

    @Test
    fun `production default and compaction retain fresh replacement behavior`() {
        val source = lowerBoundModel()
        LpScopedSolver(LpExactState(source)).use { solver ->
            assertNotNull(solver.solve())
            assertTrue(solver.push())
            assertTrue(solver.append(lowerRow(1, 2), scoped = true))
            assertEquals(0, solver.metrics.appendReplacementAttempts)
            assertTrue(solver.pop(0))

            assertTrue(solver.compact())

            assertEquals(0, solver.metrics.appendReplacementAttempts)
            assertEquals(0, solver.metrics.appendTransfers)
            assertEquals(1, solver.state.model.m)
            B5bIndependentExactSourceValidator.validate(solver.state, assertNotNull(solver.solve()))
        }
    }

    @Test
    fun `candidate selects dense and rejects sparse spiked and near singular classes`() {
        for ((shape, expected) in listOf("dense" to 1L, "sparse" to 0L, "spiked" to 0L, "near" to 0L)) {
            val model = selectorModel(shape)
            LpScopedSolver(
                LpExactState(model),
                appendSelection = LpAppendSelection.CANDIDATE,
            ).use { solver ->
                assertTrue(solver.prepare())

                assertTrue(
                    solver.append(
                        LpScopedRow(
                            16,
                            if (shape == "dense") {
                                List(2) { it to ExactLpNumber.of(1L) }
                            } else {
                                listOf(0 to ExactLpNumber.of(1L))
                            },
                            ExactLpNumber.of(1L),
                            ExactLpColumn(ExactLpBounds()),
                        ),
                        scoped = false,
                    ),
                )

                assertEquals(expected, solver.metrics.appendReplacementAttempts)
                assertEquals(expected, solver.metrics.appendTransfers)
                B5bIndependentExactSourceValidator.validate(solver.state, assertNotNull(solver.solve()))
            }
        }
    }

    @Test
    fun `candidate rejects an incomplete owner ledger before transfer`() {
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
            ): PersistentLpSolver {
                val delegate = RevisedSimplex(model, cancellation)
                return object : PersistentLpSolver by delegate {
                    override val basisLifecycleWork: BasisOperationWork get() = BasisOperationWork(complete = false)
                }
            }
        }
        LpScopedSolver(
            LpExactState(selectorModel("dense")),
            context = LpSolveContext(engineFactory = factory),
            appendSelection = LpAppendSelection.CANDIDATE,
        ).use { solver ->
            assertTrue(solver.prepare())

            assertTrue(
                solver.append(
                    LpScopedRow(
                        16,
                        List(2) { it to ExactLpNumber.of(1L) },
                        ExactLpNumber.of(1L),
                        ExactLpColumn(ExactLpBounds()),
                    ),
                    scoped = false,
                ),
            )

            assertEquals(0, solver.metrics.appendReplacementAttempts)
            assertEquals(0, solver.metrics.appendTransfers)
            assertEquals(1, solver.metrics.appendUnknownWork)
        }
    }

    @Test
    fun `intended fresh control uses the mapped basis without reporting a transfer`() {
        LpScopedSolver(
            LpExactState(lowerBoundModel()),
            appendSelection = LpAppendSelection.FRESH_INTENDED,
        ).use { solver ->
            assertNotNull(solver.solve())

            assertTrue(solver.append(lowerRow(1, 2), scoped = false))

            assertEquals(1, solver.metrics.appendReplacementAttempts)
            assertEquals(0, solver.metrics.appendTransfers)
            assertEquals(1, solver.metrics.appendIntendedFreshBuilds)
            assertEquals(0, solver.metrics.appendFallbacks)
            B5bIndependentExactSourceValidator.validate(solver.state, assertNotNull(solver.solve()))
        }
    }

    @Test
    fun `transfer decline work is retained when production fresh fallback publishes`() {
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
            ): PersistentLpSolver {
                val delegate = RevisedSimplex(model, cancellation)
                return object : PersistentLpSolver by delegate {
                    override val appendTransferReady: Boolean get() = true
                    override fun appendReplacement(
                        next: LpExactState,
                        oldRowsInNew: IntArray,
                        oldColumnsInNew: IntArray,
                        mode: LpAppendReplacementMode,
                        token: Cancellation,
                    ) = LpAppendReplacementAttempt(
                        decline = LpAppendTransferDecline.STRUCTURAL,
                        basisWork = 7,
                    )
                }
            }
        }
        LpScopedSolver(
            LpExactState(lowerBoundModel()),
            context = LpSolveContext(engineFactory = factory),
            appendSelection = LpAppendSelection.FORCE_TRANSFER,
        ).use { solver ->
            assertNotNull(solver.solve())

            assertTrue(solver.append(lowerRow(1, 2), scoped = false))

            assertEquals(1, solver.metrics.appendFallbacks)
            assertEquals(LpAppendTransferDecline.STRUCTURAL, solver.metrics.lastAppendDecline)
            assertTrue(solver.metrics.appendBasisWork >= 7)
            assertEquals(0, solver.metrics.appendUnknownWork)
            B5bIndependentExactSourceValidator.validate(solver.state, assertNotNull(solver.solve()))
        }
    }

    @Test
    fun `cancellation after transfer construction preserves the published owner and exact state`() {
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
            ): PersistentLpSolver {
                val delegate = RevisedSimplex(model, cancellation)
                return object : PersistentLpSolver by delegate {
                    override fun appendReplacement(
                        next: LpExactState,
                        oldRowsInNew: IntArray,
                        oldColumnsInNew: IntArray,
                        mode: LpAppendReplacementMode,
                        token: Cancellation,
                    ): LpAppendReplacementAttempt = delegate.appendReplacement(
                        next,
                        oldRowsInNew,
                        oldColumnsInNew,
                        mode,
                        Cancellation.Never,
                    ).also { cancelled = it.replacement != null }
                }
            }
        }
        LpScopedSolver(
            LpExactState(lowerBoundModel()),
            cancellation = token,
            context = LpSolveContext(engineFactory = factory),
            appendSelection = LpAppendSelection.FORCE_TRANSFER,
        ).use { solver ->
            val initial = assertNotNull(solver.solve())
            val state = solver.state

            assertTrue(!solver.append(lowerRow(1, 2), scoped = false))

            assertTrue(cancelled)
            assertTrue(solver.state === state)
            assertTrue(solver.lastResult === initial)
            assertEquals(1, solver.metrics.currentOwners)
            assertEquals(1, solver.metrics.closedOwners)
            cancelled = false
            B5bIndependentExactSourceValidator.validate(solver.state, assertNotNull(solver.solve()))
        }
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
            ): PersistentLpSolver = RevisedSimplex(model, cancellation, basisSolverFactory = { matrix ->
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
            })
        }
        val solver = LpScopedSolver(
            LpExactState(lowerBoundModel()),
            cancellation = token,
            context = LpSolveContext(engineFactory = factory),
            appendSelection = LpAppendSelection.FRESH_INTENDED,
        )
        assertNotNull(solver.solve())

        val thrown = assertFailsWith<IllegalStateException> {
            solver.append(lowerRow(1, 2), scoped = false)
        }

        assertTrue(thrown === primary)
        assertEquals(listOf(cleanup), thrown.suppressedExceptions.toList())
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
            ): PersistentLpSolver = RevisedSimplex(model, cancellation, basisSolverFactory = { matrix ->
                basisFactoryCalls++
                val target = basisFactoryCalls > 1
                val delegate = KotlinBasisSolver(matrix)
                object : BasisSolver by delegate {
                    override fun refactorize(basicIndex: IntArray): Boolean {
                        if (target) throw primary
                        return delegate.refactorize(basicIndex)
                    }

                    override fun close() {
                        delegate.close()
                        if (target) throw cleanup
                    }
                }
            })
        }
        val solver = LpScopedSolver(
            LpExactState(lowerBoundModel()),
            context = LpSolveContext(engineFactory = factory),
            appendSelection = LpAppendSelection.FRESH_INTENDED,
        )
        assertNotNull(solver.solve())

        val thrown = assertFailsWith<IllegalStateException> {
            solver.append(lowerRow(1, 2), scoped = false)
        }

        assertTrue(thrown === primary)
        assertEquals(listOf(cleanup), thrown.suppressedExceptions.toList())
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
            ): PersistentLpSolver {
                owners++
                val replacement = owners > 1
                val delegate = RevisedSimplex(model, cancellation)
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
        B5bIndependentExactSourceValidator.validate(solver.state, assertNotNull(solver.solve()))
        solver.close()
        assertEquals(2, closes)
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
            ): PersistentLpSolver {
                owners++
                val replacement = owners > 1
                val delegate = RevisedSimplex(model, cancellation)
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
        assertTrue(solver.state === state)
        assertTrue(solver.lastResult === initial)
        B5bIndependentExactSourceValidator.validate(solver.state, assertNotNull(solver.solve()))
        solver.close()
    }

    @Test
    fun `malformed transfer mapping declines before fresh fallback`() {
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
            ): PersistentLpSolver {
                val delegate = RevisedSimplex(model, cancellation)
                return object : PersistentLpSolver by delegate {
                    override fun appendReplacement(
                        next: LpExactState,
                        oldRowsInNew: IntArray,
                        oldColumnsInNew: IntArray,
                        mode: LpAppendReplacementMode,
                        token: Cancellation,
                    ): LpAppendReplacementAttempt = delegate.appendReplacement(
                        next,
                        IntArray(oldRowsInNew.size) { -1 },
                        oldColumnsInNew,
                        mode,
                        token,
                    )
                }
            }
        }
        LpScopedSolver(
            LpExactState(lowerBoundModel()),
            context = LpSolveContext(engineFactory = factory),
            appendSelection = LpAppendSelection.FORCE_TRANSFER,
        ).use { solver ->
            assertNotNull(solver.solve())

            assertTrue(solver.append(lowerRow(1, 2), scoped = false))

            assertEquals(1, solver.metrics.appendFallbacks)
            assertEquals(LpAppendTransferDecline.INCOMPATIBLE_STATE, solver.metrics.lastAppendDecline)
            B5bIndependentExactSourceValidator.validate(solver.state, assertNotNull(solver.solve()))
        }
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

    private fun selectorModel(shape: String): ExactLpModel {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val columns = List(8) { column ->
            when (shape) {
                "dense" -> List(16) { row -> ExactLpEntry(row, ExactLpNumber.of((column + row) % 3 + 1L)) }

                "spiked" -> if (column == 0) {
                    List(16) { row -> ExactLpEntry(row, ExactLpNumber.of(1_000_000L)) }
                } else {
                    listOf(ExactLpEntry(column * 2, one))
                }

                "near" -> listOf(
                    ExactLpEntry(column * 2, if (column == 7) ExactLpNumber.ofIeee(1e-12) else one),
                )

                else -> listOf(ExactLpEntry(column * 2, one))
            }
        }
        return ExactLpModel(
            columns,
            List(16) { one },
            List(8) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one))) } +
                List(16) { ExactLpColumn(ExactLpBounds()) },
            List(16) { ExactLpRow() },
            ExactLpObjective(List(24) { zero }),
        )
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
