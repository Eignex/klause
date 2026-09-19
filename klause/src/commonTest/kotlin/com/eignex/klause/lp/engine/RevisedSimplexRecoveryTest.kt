package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisArithmeticException
import com.eignex.klause.simplex.basis.BasisRepairControl
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.IndexedVector
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RevisedSimplexRecoveryTest {
    @Test
    fun `late cancellation of recovered original costs withholds all publication`() {
        for (root in listOf(false, true)) {
            val b = LpBuilder()
            val x = b.addVar(0L, 2L, cost = 1L)
            b.addRow(mapOf(x to 1L), Relation.GE, 1L)
            val model = b.build(Sense.MINIMIZE)
            var fail = true
            var cancelled = false
            lateinit var solver: RevisedSimplex
            solver = RevisedSimplex(
                model,
                cancellation = Cancellation { cancelled },
                perturbationOptions = CostPerturbationOptions(root = root),
                recoveryOptions = NumericalRecoveryOptions(enabled = true),
                basisSolverFactory = { matrix ->
                    val delegate = KotlinBasisSolver(matrix)
                    object : BasisSolver by delegate {
                        override fun ftran(x: IndexedVector, expectedDensity: Double) {
                            if (fail) {
                                fail = false
                                throw BasisArithmeticException("initial solve")
                            }
                            delegate.ftran(x, expectedDensity)
                        }
                        override fun btran(x: IndexedVector, expectedDensity: Double) {
                            delegate.btran(x, expectedDensity)
                            if (solver.lastPivots > 0) cancelled = true
                        }
                    }
                },
            )

            assertNull(solver.solve())

            assertTrue(cancelled)
            assertEquals(1, solver.lastPivots)
            assertEquals(0, solver.lastNumericalMetrics.cleanupSuccesses)
            assertEquals(setOf(NumericalRecoveryStep.RESIDUAL_REBUILD), solver.lastNumericalMetrics.recovery.keys)
            assertTrue(solver.lastNumericalMetrics.recovery.values.all { it.successes == 0 })
            assertNull(solver.infeasibleRay)
            assertNull(solver.solvedExactState)
            assertTrue(solver.gomoryCuts(1).isEmpty())
            assertEquals(1L, model.cost[x])
            solver.close()
        }
    }

    @Test
    fun `shift removal during fallback retains the cleanup obligation at an iteration stop`() {
        for (recovery in listOf(false, true)) {
            val b = LpBuilder()
            val x = b.addVar(0L, 2L, cost = 1L)
            b.addRow(mapOf(x to 1024L), Relation.GE, 1024L)
            val model = b.build(Sense.MINIMIZE)
            var fail = true
            val solver = RevisedSimplex(
                model,
                iterationLimit = 2,
                perturbationOptions = CostPerturbationOptions(root = true),
                recoveryOptions = NumericalRecoveryOptions(enabled = recovery),
                basisSolverFactory = { matrix ->
                    val delegate = KotlinBasisSolver(matrix)
                    object : BasisSolver by delegate {
                        override fun ftran(x: IndexedVector, expectedDensity: Double) {
                            if (fail) {
                                fail = false
                                throw BasisArithmeticException("first iteration")
                            }
                            delegate.ftran(x, expectedDensity)
                        }
                    }
                },
            )

            assertNull(solver.solve())

            assertEquals(1, solver.lastPivots)
            assertEquals(1, solver.lastNumericalMetrics.rootAttempts)
            assertEquals(1, solver.lastNumericalMetrics.capExits)
            assertNull(solver.infeasibleRay)
            assertNull(solver.solvedExactState)
            assertTrue(solver.gomoryCuts(1).isEmpty())
            assertEquals(1L, model.cost[x])
            solver.close()
        }
    }

    @Test
    fun `exact singularity rejects the numerical heading and permits a different source basis`() {
        val b = LpBuilder()
        repeat(2) { b.addVar(0L, 2L) }
        repeat(2) { b.addRow(mapOf(0 to 1L, 1 to 1L), Relation.EQ, 1L) }
        val model = b.build(Sense.MINIMIZE)
        val heading = intArrayOf(0, 1)
        val solver = RevisedSimplex(model, basisSolverFactory = { matrix ->
            val delegate = KotlinBasisSolver(matrix)
            object : BasisSolver by delegate {
                override fun refactorize(basicIndex: IntArray): Boolean =
                    delegate.refactorize(if (basicIndex.contentEquals(heading)) intArrayOf(2, 3) else basicIndex)
            }
        })
        val proposal = assertNotNull(
            solver.solve(
                Basis(
                    heading,
                    arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.FIXED, VarStatus.FIXED),
                ),
            ),
        )
        val rejected = verifyExactBasis(model, proposal.basis)
        assertEquals(ExactBasisDecline.SINGULAR, rejected.metrics.decline)
        assertEquals(1, rejected.singularRank)
        assertTrue(solver.rejectSingularBasis(model, proposal.basis))

        val recovered = assertNotNull(solver.resolveBounds())

        assertTrue(!recovered.basis.basicVars.contentEquals(heading))
        assertEquals(1.0, recovered.primal.sum(), 1e-9)
        assertTrue(recovered.primal.all { it in 0.0..2.0 })
        assertNotNull(verifyExactBasis(model, recovered.basis).witness)
        solver.close()
    }

    @Test
    fun `appended owner retains recovery selection and restores original costs`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = LpExactState(
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
        var failNext = false
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
                model, cancellation, refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy,
                pricing = pricing, perturbationOptions = CostPerturbationOptions(root = true),
                recoveryOptions = NumericalRecoveryOptions(enabled = true),
                basisSolverFactory = { matrix ->
                    val delegate = KotlinBasisSolver(matrix)
                    object : BasisSolver by delegate {
                        override fun btran(x: IndexedVector, expectedDensity: Double) {
                            if (failNext) {
                                failNext = false
                                throw BasisArithmeticException("appended solve")
                            }
                            delegate.btran(x, expectedDensity)
                        }
                    }
                },
            )
        }
        LpScopedSolver(
            source,
            context = LpSolveContext(engineFactory = factory),
            appendSelection = LpAppendSelection.FRESH_INTENDED,
        ).use { owner ->
            assertNotNull(owner.solve())
            assertTrue(
                owner.append(
                    LpScopedRow(
                        1L,
                        listOf(0 to ExactLpNumber.of(-1L)),
                        ExactLpNumber.of(-1L),
                        ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
                    ),
                    scoped = false,
                ),
            )
            failNext = true

            val (engine, result) = assertNotNull(owner.solveFloat())

            assertEquals(1.0, assertNotNull(result).objective)
            assertEquals(
                1,
                (engine as RevisedSimplex).lastNumericalMetrics.recovery.getValue(
                    NumericalRecoveryStep.RESIDUAL_REBUILD,
                ).successes,
            )
            assertFalse(failNext)
            assertEquals(1, owner.metrics.appendIntendedFreshBuilds)
        }
    }

    @Test
    fun `recovery rebuild spends the remaining work without admitting another solve`() {
        val b = LpBuilder()
        val x = b.addVar(0L, 2L)
        b.addRow(mapOf(x to 1L), Relation.GE, 1L)
        var solves = 0
        val solver = RevisedSimplex(
            b.build(Sense.MINIMIZE),
            workLimit = 4L,
            recoveryOptions = NumericalRecoveryOptions(enabled = true),
            basisSolverFactory = { matrix ->
                val delegate = KotlinBasisSolver(matrix)
                object : BasisSolver by delegate {
                    override fun ftran(x: IndexedVector, expectedDensity: Double) {
                        solves++
                        throw BasisArithmeticException("injected solve failure")
                    }
                }
            },
        )

        assertNull(solver.solve())

        assertEquals(1, solves)
        assertEquals(4L, solver.lastWorkOps)
        assertEquals(setOf(NumericalRecoveryStep.RESIDUAL_REBUILD), solver.lastNumericalMetrics.recovery.keys)
        assertEquals(1, solver.lastNumericalMetrics.capExits)
        solver.close()
    }

    @Test
    fun `scale fallback cannot reaccept an exactly rejected heading`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val coefficient = ExactLpNumber.of(-1024L)
        val state = LpExactState(
            ExactLpModel(
                listOf(listOf(ExactLpEntry(0, coefficient))),
                listOf(coefficient),
                listOf(
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(2L)))),
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
                ),
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(one, zero)),
            ),
        )
        val model = assertNotNull(state.toWorkingModel())
        var failScaled = false
        lateinit var solver: RevisedSimplex
        solver = RevisedSimplex(
            model,
            perturbationOptions = CostPerturbationOptions(root = true),
            recoveryOptions = NumericalRecoveryOptions(enabled = true),
            basisSolverFactory = { matrix ->
                val delegate = KotlinBasisSolver(matrix)
                object : BasisSolver by delegate {
                    override fun ftran(x: IndexedVector, expectedDensity: Double) {
                        if (failScaled && solver.scalingMetrics.applied) {
                            throw BasisArithmeticException("scaled failure")
                        }
                        delegate.ftran(x, expectedDensity)
                    }
                }
            },
        )
        val first = assertNotNull(solver.solve())
        assertTrue(solver.rejectSingularBasis(model, first.basis))
        failScaled = true

        assertNull(solver.solve())

        assertEquals(1, solver.scalingMetrics.fallbacks)
        assertEquals(1, solver.lastNumericalMetrics.rootAttempts)
        assertNull(solver.continuationBasis(model))
        assertNull(solver.captureBasisRestart(Cancellation.Never))
        assertNull(solver.solvedExactState)
        solver.close()
    }

    @Test
    fun `a repaired factorization does not turn later unboundedness into a numerical retry`() {
        for (recovery in listOf(false, true)) {
            val b = LpBuilder()
            val x = b.addRealVar(0.0, Double.MAX_VALUE, cost = -1.0)
            b.addRealRow(intArrayOf(x), doubleArrayOf(1024.0), Relation.GE, 0.0)
            var first = true
            val solver = RevisedSimplex(
                b.build(Sense.MINIMIZE),
                recoveryOptions = NumericalRecoveryOptions(enabled = recovery),
                basisSolverFactory = { matrix ->
                    val delegate = KotlinBasisSolver(matrix)
                    object : BasisSolver by delegate {
                        override fun refactorize(basicIndex: IntArray): Boolean {
                            if (first) {
                                first = false
                                return false
                            }
                            return delegate.refactorize(basicIndex)
                        }
                    }
                },
            )

            assertNull(solver.solvePrimal())

            assertEquals(1, solver.lastSingularRefactorizations)
            assertTrue(solver.lastNumericalMetrics.recovery.isEmpty())
            assertEquals(0, solver.scalingMetrics.fallbacks)
            solver.close()
        }
    }

    @Test
    fun `unscaled recovery discards scaled shifts without another root admission`() {
        val b = LpBuilder()
        val x = b.addVar(0L, 2L, cost = 1L)
        b.addRow(mapOf(x to 1024L), Relation.GE, 1024L)
        val model = b.build(Sense.MINIMIZE)
        lateinit var solver: RevisedSimplex
        solver = RevisedSimplex(
            model,
            perturbationOptions = CostPerturbationOptions(root = true),
            recoveryOptions = NumericalRecoveryOptions(enabled = true),
            basisSolverFactory = { matrix ->
                val delegate = KotlinBasisSolver(matrix)
                object : BasisSolver by delegate {
                    override fun ftran(x: IndexedVector, expectedDensity: Double) {
                        if (solver.scalingMetrics.applied) throw BasisArithmeticException("scaled failure")
                        delegate.ftran(x, expectedDensity)
                    }
                }
            },
        )

        val result = assertNotNull(solver.solve())

        assertEquals(1, solver.lastNumericalMetrics.rootAttempts)
        assertEquals(1, solver.lastNumericalMetrics.recovery.getValue(NumericalRecoveryStep.UNSCALED).successes)
        assertEquals(1.0, result.objective)
        assertEquals(1L, integerDualLowerBoundCeil(model, result.duals))
        solver.close()
    }

    @Test
    fun `each supported recovery step can produce an original source optimum`() {
        for (target in NumericalRecoveryStep.entries.filter { it != NumericalRecoveryStep.TIGHTER_PIVOT }) {
            val b = LpBuilder()
            val x = b.addVar(0L, 2L, cost = 1L)
            b.addRow(mapOf(x to 1024L), Relation.GE, 1024L)
            val model = b.build(Sense.MINIMIZE)
            lateinit var solver: RevisedSimplex
            solver = RevisedSimplex(
                model,
                recoveryOptions = NumericalRecoveryOptions(enabled = true),
                basisSolverFactory = { matrix ->
                    val delegate = KotlinBasisSolver(matrix)
                    object : BasisSolver by delegate {
                        override fun ftran(x: IndexedVector, expectedDensity: Double) {
                            if (solver.lastNumericalMetrics.recovery[target]?.attempts != 1) {
                                throw BasisArithmeticException("injected until $target")
                            }
                            delegate.ftran(x, expectedDensity)
                        }
                    }
                },
            )

            val result = assertNotNull(solver.solve(), target.name)

            assertEquals(1.0, result.primal[x], 1e-9, target.name)
            assertEquals(1.0, result.objective, 1e-9, target.name)
            assertEquals(1L, integerDualLowerBoundCeil(model, result.duals), target.name)
            assertEquals(1, solver.lastNumericalMetrics.recovery.getValue(target).successes)
            assertTrue(solver.lastNumericalMetrics.recovery.values.all { it.attempts <= 1 })
            if (target != NumericalRecoveryStep.RESIDUAL_REBUILD) {
                assertEquals(
                    1,
                    solver.lastNumericalMetrics.recovery.getValue(NumericalRecoveryStep.TIGHTER_PIVOT).skips,
                )
            }
            solver.close()
        }
    }

    @Test
    fun `exhausted recovery ladder declines without an original source claim`() {
        val b = LpBuilder()
        val x = b.addVar(0L, 2L, cost = 1L)
        b.addRow(mapOf(x to 1L), Relation.GE, 1L)
        val solver = RevisedSimplex(
            b.build(Sense.MINIMIZE),
            recoveryOptions = NumericalRecoveryOptions(enabled = true),
            basisSolverFactory = { matrix ->
                val delegate = KotlinBasisSolver(matrix)
                object : BasisSolver by delegate {
                    override fun ftran(x: IndexedVector, expectedDensity: Double): Unit =
                        throw BasisArithmeticException(
                            "injected",
                        )
                }
            },
        )

        assertNull(solver.solve())

        assertEquals(NumericalRecoveryStep.entries.toSet(), solver.lastNumericalMetrics.recovery.keys)
        assertEquals(1, solver.lastNumericalMetrics.recovery.getValue(NumericalRecoveryStep.UNSCALED).skips)
        assertTrue(solver.lastNumericalMetrics.recovery.values.all { it.successes == 0 })
        assertNull(solver.infeasibleRay)
        assertNull(solver.solvedExactState)
        assertTrue(solver.gomoryCuts(1).isEmpty())
        solver.close()
    }

    @Test
    fun `repair resource stop prevents all later recovery steps`() {
        val b = LpBuilder()
        val x = b.addVar(0L, 2L)
        b.addRow(mapOf(x to 1L), Relation.GE, 1L)
        var builds = 0
        val solver = RevisedSimplex(
            b.build(Sense.MINIMIZE),
            workLimit = 100L,
            recoveryOptions = NumericalRecoveryOptions(enabled = true),
            basisSolverFactory = { matrix ->
                val delegate = KotlinBasisSolver(matrix)
                object : BasisSolver by delegate {
                    override fun refactorize(basicIndex: IntArray): Boolean {
                        builds++
                        return false
                    }
                    override fun refactorizeRepairing(basicIndex: IntArray, control: BasisRepairControl): Nothing? {
                        control.charge(100L)
                        control.check()
                        return null
                    }
                }
            },
        )

        assertNull(solver.solve())

        assertEquals(1, builds)
        assertTrue(solver.lastNumericalMetrics.recovery.isEmpty())
        assertTrue(solver.lastWorkOps >= 100L)
        assertNull(solver.infeasibleRay)
        solver.close()
    }

    @Test
    fun `recovery stops before another step when cancellation arrives`() {
        val b = LpBuilder()
        val x = b.addVar(0L, 2L)
        b.addRow(mapOf(x to 1L), Relation.GE, 1L)
        var cancelled = false
        var builds = 0
        val solver = RevisedSimplex(
            b.build(Sense.MINIMIZE),
            cancellation = Cancellation { cancelled },
            recoveryOptions = NumericalRecoveryOptions(enabled = true),
            basisSolverFactory = { matrix ->
                val delegate = KotlinBasisSolver(matrix)
                object : BasisSolver by delegate {
                    override fun refactorize(basicIndex: IntArray): Boolean {
                        builds++
                        return delegate.refactorize(basicIndex)
                    }
                    override fun ftran(x: IndexedVector, expectedDensity: Double) {
                        cancelled = true
                        throw BasisArithmeticException("cancel after numerical failure")
                    }
                }
            },
        )

        assertNull(solver.solve())

        assertEquals(1, builds)
        assertTrue(solver.lastNumericalMetrics.recovery.isEmpty())
        assertNull(solver.infeasibleRay)
        solver.close()
    }

    @Test
    fun `exactly rejected heading cannot be reexported after a cold retry`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val negative = ExactLpNumber.of(-1L)
        val state = LpExactState(
            ExactLpModel(
                listOf(listOf(ExactLpEntry(0, negative))),
                listOf(negative),
                listOf(
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(2L)))),
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
                ),
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(one, zero)),
            ),
        )
        val model = assertNotNull(state.toWorkingModel())
        val solver = RevisedSimplex(model, recoveryOptions = NumericalRecoveryOptions(enabled = true))
        val first = assertNotNull(solver.solve())
        assertTrue(solver.rejectSingularBasis(model, first.basis))

        assertNull(solver.solve())

        assertNull(solver.continuationBasis(model))
        assertNull(solver.captureBasisRestart(Cancellation.Never))
        assertNull(solver.solvedExactState)
        assertNull(solver.infeasibleRay)
        solver.close()
    }
}
