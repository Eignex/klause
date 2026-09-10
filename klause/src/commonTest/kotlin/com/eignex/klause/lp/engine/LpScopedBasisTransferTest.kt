package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisArithmeticException
import com.eignex.klause.simplex.basis.BasisExtension
import com.eignex.klause.simplex.basis.BasisExtensionResult
import com.eignex.klause.simplex.basis.BasisOperationWork
import com.eignex.klause.simplex.basis.BasisPhaseWork
import com.eignex.klause.simplex.basis.BasisRepair
import com.eignex.klause.simplex.basis.BasisSnapshot
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.IndexedVector
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.koblas.SparseMatrix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpScopedBasisTransferTest {
    @Test
    fun `repaired mixed headings transfer and restore on the replacement owner`() {
        val repairedOwners = mutableListOf<MixedRepairBasisSolver>()
        val transferredPolicyBaselines = mutableListOf<RefactorPolicyMetrics>()
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
            ): PersistentLpSolver {
                val delegate = RevisedSimplex(
                    model,
                    cancellation,
                    refactorUpdateLimit = 64,
                    iterationLimit = iterationLimit,
                    workLimit = workLimit,
                    trackDegeneracy = trackDegeneracy,
                    basisSolverFactory = { matrix ->
                        MixedRepairBasisSolver(KotlinBasisSolver(matrix)).also { repairedOwners.add(it) }
                    },
                )
                return object : PersistentLpSolver by delegate {
                    override fun appendReplacement(
                        next: LpExactState,
                        oldRowsInNew: IntArray,
                        oldColumnsInNew: IntArray,
                        mode: LpAppendReplacementMode,
                        token: Cancellation,
                    ): LpAppendReplacementAttempt {
                        val attempt = delegate.appendReplacement(next, oldRowsInNew, oldColumnsInNew, mode, token)
                        val replacement = attempt.replacement?.solver
                        if (replacement is RevisedSimplex) {
                            transferredPolicyBaselines.add(replacement.lastRefactorPolicyMetrics)
                        }
                        return attempt
                    }
                }
            }
        }
        LpScopedSolver(
            LpExactState(repairCompositionModel()),
            context = LpSolveContext(engineFactory = factory),
            refactorUpdateLimit = 1,
            appendSelection = LpAppendSelection.FORCE_TRANSFER,
        ).use { solver ->
            B5bIndependentExactSourceValidator.validate(solver.state, assertNotNull(solver.solve()))
            val recoverySnapshot = assertNotNull(solver.captureBasisRestart())
            val repairedOwner = repairedOwners.single()
            repairedOwner.armRepair()
            assertTrue(solver.restoreBasisRestart(recoverySnapshot))
            recoverySnapshot.close()
            assertTrue(repairedOwner.installedMixedRepair)

            assertTrue(solver.append(lowerRow(3, 2), scoped = false))
            assertTrue(repairedOwners.any { it.extendedMixedRepair })
            assertNotNull(transferredPolicyBaselines.single().freshFactorNnz)
            val transferred = assertNotNull(solver.solve())
            B5bIndependentExactSourceValidator.validate(solver.state, transferred)
            assertEquals(1, solver.metrics.appendTransfers)
            assertEquals(0, solver.metrics.appendFallbacks)

            val snapshot = assertNotNull(solver.captureBasisRestart())
            val before = assertNotNull(solver.basisLifecycleWork).units
            assertTrue(solver.assertBound(0, false, ExactLpSide(ExactLpNumber.of(4L)), witness = 99L))
            B5bIndependentExactSourceValidator.validate(solver.state, assertNotNull(solver.solve()))
            assertTrue(solver.restoreBasisRestart(snapshot))
            val restored = assertNotNull(solver.solve())
            B5bIndependentExactSourceValidator.validate(solver.state, restored)
            assertTrue(assertNotNull(solver.basisLifecycleWork).units > before)

            LpScopedSolver(solver.state).use { fresh ->
                assertEquals(assertNotNull(fresh.solve()).lowerBound, restored.lowerBound)
            }
            snapshot.close()
        }
    }

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
            val settledWork = solver.metrics.appendBasisWork

            assertEquals(0, solver.metrics.appendReplacementAttempts)
            assertEquals(0, solver.metrics.appendTransfers)
            assertEquals(1, solver.state.model.m)
            B5bIndependentExactSourceValidator.validate(solver.state, assertNotNull(solver.solve()))
            assertEquals(settledWork, solver.metrics.appendBasisWork)
            assertEquals(0, solver.metrics.appendUnknownWork)
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
    fun `failed intended fresh build retains work before production fallback`() {
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
                val rejected = basisFactoryCalls == 2
                val delegate = KotlinBasisSolver(matrix)
                object : BasisSolver by delegate {
                    override val basisOperationWork: BasisOperationWork
                        get() = if (rejected) {
                            BasisOperationWork(
                                refactorization = BasisPhaseWork(attempts = 1, units = 9, declines = 1),
                            )
                        } else {
                            delegate.basisOperationWork
                        }

                    override fun refactorize(basicIndex: IntArray): Boolean =
                        !rejected && delegate.refactorize(basicIndex)
                }
            })
        }
        LpScopedSolver(
            LpExactState(lowerBoundModel()),
            context = LpSolveContext(engineFactory = factory),
            appendSelection = LpAppendSelection.FRESH_INTENDED,
        ).use { solver ->
            assertNotNull(solver.solve())

            assertTrue(solver.append(lowerRow(1, 2), scoped = false))

            assertEquals(LpAppendTransferDecline.FRESH_FAILED, solver.metrics.lastAppendDecline)
            assertEquals(1, solver.metrics.appendFallbacks)
            assertTrue(solver.metrics.appendBasisWork >= 9)
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
            ): PersistentLpSolver {
                owners++
                val replacement = owners > 1
                val delegate = RevisedSimplex(model, cancellation)
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
    fun `typed arithmetic decline retains partial units and incomplete status`() {
        val oldMatrix = SparseMatrix.ofColumns(1, 1, listOf(listOf(0 to 1.0)))
        val newMatrix = SparseMatrix.ofColumns(
            2,
            2,
            listOf(listOf(0 to 1.0), listOf(1 to 1.0)),
        )
        val delegate = KotlinBasisSolver(oldMatrix)
        var reads = 0
        val old = object : BasisSolver by delegate {
            override val basisOperationWork: BasisOperationWork
                get() = if (reads++ == 0) {
                    BasisOperationWork()
                } else {
                    BasisOperationWork(
                        extension = BasisPhaseWork(attempts = 1, units = 7, declines = 1),
                        complete = false,
                    )
                }

            override fun extend(matrix: SparseMatrix, extension: BasisExtension): BasisExtensionResult? =
                throw BasisArithmeticException("injected extension")
        }

        val attempt = BasisExtensionAdapter().transfer(
            old,
            newMatrix,
            intArrayOf(0, 1),
            intArrayOf(0, 1),
            BasisExtension(intArrayOf(0), intArrayOf(-1), intArrayOf(0), intArrayOf(0)),
        )

        assertTrue(attempt.arithmeticDeclined)
        assertEquals(7L, attempt.workUnits)
        assertTrue(!attempt.workComplete)
        old.close()
    }

    @Test
    fun `untyped transfer failure retains partial units and incomplete status`() {
        val primary = IllegalStateException("primary")
        val oldMatrix = SparseMatrix.ofColumns(1, 1, listOf(listOf(0 to 1.0)))
        val newMatrix = SparseMatrix.ofColumns(
            2,
            2,
            listOf(listOf(0 to 1.0), listOf(1 to 1.0)),
        )
        val delegate = KotlinBasisSolver(oldMatrix)
        var reads = 0
        val old = object : BasisSolver by delegate {
            override val basisOperationWork: BasisOperationWork
                get() = if (reads++ == 0) {
                    BasisOperationWork()
                } else {
                    BasisOperationWork(
                        extension = BasisPhaseWork(attempts = 1, units = 7, declines = 1),
                        complete = false,
                    )
                }

            override fun extend(matrix: SparseMatrix, extension: BasisExtension): BasisExtensionResult? = throw primary
        }
        val adapter = BasisExtensionAdapter()

        val thrown = assertFailsWith<IllegalStateException> {
            adapter.transfer(
                old,
                newMatrix,
                intArrayOf(0, 1),
                intArrayOf(0, 1),
                BasisExtension(intArrayOf(0), intArrayOf(-1), intArrayOf(0), intArrayOf(0)),
            )
        }

        assertTrue(thrown === primary)
        assertEquals(7, assertNotNull(adapter.lastAttemptWork).units)
        assertTrue(!assertNotNull(adapter.lastAttemptWork).complete)
        old.close()
    }

    @Test
    fun `scoped incomplete decline keeps partial units and records unknown work`() {
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
                    ) = LpAppendReplacementAttempt(
                        decline = LpAppendTransferDecline.ARITHMETIC,
                        basisWork = 7,
                        basisWorkComplete = false,
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

            assertTrue(solver.metrics.appendBasisWork >= 7)
            assertEquals(1, solver.metrics.appendUnknownWork)
            assertEquals(LpAppendTransferDecline.ARITHMETIC, solver.metrics.lastAppendDecline)
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
            ): PersistentLpSolver {
                val delegate = RevisedSimplex(model, cancellation)
                return object : PersistentLpSolver by delegate {
                    override fun appendReplacement(
                        next: LpExactState,
                        oldRowsInNew: IntArray,
                        oldColumnsInNew: IntArray,
                        mode: LpAppendReplacementMode,
                        token: Cancellation,
                    ) = LpAppendReplacementAttempt(
                        decline = LpAppendTransferDecline.STRUCTURAL,
                        basisWork = contribution,
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
            assertTrue(solver.append(lowerRow(2, 3), scoped = false))

            assertEquals(Long.MAX_VALUE, solver.metrics.appendBasisWork)
            assertTrue(solver.metrics.appendUnknownWork > 0)
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
            ): PersistentLpSolver = RevisedSimplex(model, cancellation, basisSolverFactory = { matrix ->
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
            ): PersistentLpSolver = RevisedSimplex(model, cancellation, basisSolverFactory = { matrix ->
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

        assertTrue(thrown === cleanup)
        assertEquals(7, solver.metrics.appendBasisWork)
        assertEquals(0, solver.metrics.appendUnknownWork)
        solver.close()
    }

    @Test
    fun `transfer telemetry failure closes the extended basis owner`() {
        val telemetry = IllegalStateException("telemetry")
        val cleanup = IllegalArgumentException("cleanup")
        var ledgerReads = 0
        var extendedCloses = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
            ): PersistentLpSolver = RevisedSimplex(model, cancellation, basisSolverFactory = { matrix ->
                val delegate = KotlinBasisSolver(matrix)
                object : BasisSolver by delegate {
                    override val basisOperationWork: BasisOperationWork
                        get() {
                            ledgerReads++
                            if (ledgerReads > 1) throw telemetry
                            return delegate.basisOperationWork
                        }

                    override fun extend(
                        matrix: com.eignex.koblas.SparseMatrix,
                        extension: BasisExtension,
                    ): BasisExtensionResult? = delegate.extend(matrix, extension)?.let { result ->
                        val extended = object : BasisSolver by result.solver {
                            override fun close() {
                                extendedCloses++
                                result.solver.close()
                                throw cleanup
                            }
                        }
                        BasisExtensionResult(extended, result.basis.columns, result.basis.unitRows)
                    }
                }
            })
        }
        val solver = LpScopedSolver(
            LpExactState(lowerBoundModel()),
            context = LpSolveContext(engineFactory = factory),
            appendSelection = LpAppendSelection.FORCE_TRANSFER,
        )
        val initial = assertNotNull(solver.solve())
        val state = solver.state
        ledgerReads = 0

        val thrown = assertFailsWith<IllegalStateException> {
            solver.append(lowerRow(1, 2), scoped = false)
        }

        assertTrue(thrown === telemetry)
        assertEquals(listOf(cleanup), thrown.suppressedExceptions.toList())
        assertEquals(1, extendedCloses)
        assertTrue(solver.state === state)
        assertTrue(solver.lastResult === initial)
        B5bIndependentExactSourceValidator.validate(solver.state, assertNotNull(solver.solve()))
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
            ): PersistentLpSolver = RevisedSimplex(model, cancellation, basisSolverFactory = { matrix ->
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
            })
        }
        val solver = LpScopedSolver(
            LpExactState(lowerBoundModel()),
            context = LpSolveContext(engineFactory = factory),
            appendSelection = LpAppendSelection.FRESH_INTENDED,
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
            ): PersistentLpSolver {
                owners++
                val replacement = owners > 1
                val delegate = RevisedSimplex(model, cancellation)
                return object : PersistentLpSolver by delegate {
                    override val basisLifecycleWork: BasisOperationWork
                        get() {
                            if (replacement && replacementLedgerReads++ > 0) throw telemetry
                            return checkNotNull(delegate.basisLifecycleWork)
                        }

                    override fun resolveBounds(): FloatLpResult? {
                        if (replacement) throw primary
                        return delegate.resolveBounds()
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
        assertEquals(1, solver.metrics.appendUnknownWork)
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

    private fun repairCompositionModel(): ExactLpModel {
        val zero = ExactLpNumber.of(0L)
        val minusOne = ExactLpNumber.of(-1L)
        val structural = ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L))))
        val logical = ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))
        return ExactLpModel(
            listOf(
                listOf(ExactLpEntry(0, minusOne), ExactLpEntry(2, minusOne)),
                listOf(ExactLpEntry(0, minusOne), ExactLpEntry(1, minusOne)),
                listOf(ExactLpEntry(1, minusOne), ExactLpEntry(2, minusOne)),
            ),
            listOf(ExactLpNumber.of(-3L), ExactLpNumber.of(-4L), ExactLpNumber.of(-5L)),
            List(3) { structural } + List(3) { logical },
            List(3) { ExactLpRow() },
            ExactLpObjective(
                listOf(ExactLpNumber.of(1L), ExactLpNumber.of(2L), ExactLpNumber.of(3L), zero, zero, zero),
            ),
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

private class MixedRepairBasisSolver(private val delegate: BasisSolver) : BasisSolver by delegate {
    private var repairArmed = false
    var installedMixedRepair = false
        private set
    var extendedMixedRepair = false
        private set

    fun armRepair() {
        repairArmed = true
    }

    override fun refactorize(basicIndex: IntArray): Boolean {
        if (!repairArmed) return delegate.refactorize(basicIndex)
        repairArmed = false
        return false
    }

    override fun refactorizeRepairing(basicIndex: IntArray): BasisRepair? {
        if (n < 2) return null
        val requested = IntArray(n)
        requested[n - 1] = 1
        val repair = delegate.refactorizeRepairing(requested) ?: return null
        installedMixedRepair = repair.repaired
        return repair
    }

    override fun snapshot(): BasisSnapshot? = null

    override fun extend(matrix: SparseMatrix, extension: BasisExtension): BasisExtensionResult? {
        val requested = extension.basis
        assertTrue(installedMixedRepair)
        assertTrue(
            requested.repaired,
            "columns=${requested.columns.contentToString()} units=${requested.unitRows.contentToString()}",
        )
        extendedMixedRepair = true
        return delegate.extend(matrix, extension)
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
