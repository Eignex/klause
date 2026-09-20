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
    fun `late cancellation after unscaled fallback withholds all publication`() {
        val b = LpBuilder()
        val x = b.addVar(0L, 2L, cost = 1L)
        b.addRow(mapOf(x to 1024L), Relation.GE, 1024L)
        val model = assertNotNull(
            LpExactState(assertNotNull(b.build(Sense.MINIMIZE).authoritativeModel())).toWorkingModel(),
        )
        var fail = true
        var cancelled = false
        lateinit var solver: RevisedSimplex
        solver = RevisedSimplex(
            model,
            cancellation = Cancellation { cancelled },
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
        assertNull(solver.infeasibleRay)
        assertNull(solver.solvedExactState)
        assertTrue(solver.gomoryCuts(1).isEmpty())
        assertEquals(1.0, model.costD(x))
        solver.close()
    }

    @Test
    fun `unscaled fallback shares the iteration allowance`() {
        val b = LpBuilder()
        val x = b.addVar(0L, 2L, cost = 1L)
        b.addRow(mapOf(x to 1024L), Relation.GE, 1024L)
        val model = b.build(Sense.MINIMIZE)
        var fail = true
        val solver = RevisedSimplex(
            model,
            iterationLimit = 2,
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

        val result = assertNotNull(solver.solve())

        assertFalse(result.optimal)
        assertEquals(1L, integerDualLowerBoundCeil(model, result.duals))
        assertEquals(1, solver.scalingMetrics.fallbacks)

        assertEquals(1, solver.lastPivots)
        assertEquals(1, solver.lastNumericalMetrics.capExits)
        assertNull(solver.infeasibleRay)
        assertNull(solver.solvedExactState)
        assertTrue(solver.gomoryCuts(1).isEmpty())
        assertEquals(1L, model.cost[x])
        solver.close()
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
    fun `unscaled fallback shares the work allowance`() {
        val b = LpBuilder()
        val x = b.addVar(0L, 2L)
        b.addRow(mapOf(x to 1024L), Relation.GE, 1024L)
        var solves = 0
        val solver = RevisedSimplex(
            b.build(Sense.MINIMIZE),
            workLimit = 4L,
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
        assertNull(solver.continuationBasis(model))
        assertNull(solver.solvedExactState)
        solver.close()
    }

    @Test
    fun `a repaired factorization does not turn later unboundedness into a numerical retry`() {
        val b = LpBuilder()
        val x = b.addRealVar(0.0, Double.MAX_VALUE, cost = -1.0)
        b.addRealRow(intArrayOf(x), doubleArrayOf(1024.0), Relation.GE, 0.0)
        var first = true
        val solver = RevisedSimplex(
            b.build(Sense.MINIMIZE),
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
        assertEquals(0, solver.scalingMetrics.fallbacks)
        solver.close()
    }

    @Test
    fun `unscaled fallback retains true costs and a certified bound`() {
        val b = LpBuilder()
        val x = b.addVar(0L, 2L, cost = 1L)
        b.addRow(mapOf(x to 1024L), Relation.GE, 1024L)
        val model = b.build(Sense.MINIMIZE)
        lateinit var solver: RevisedSimplex
        solver = RevisedSimplex(
            model,
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

        assertEquals(1.0, result.objective)
        assertEquals(1L, integerDualLowerBoundCeil(model, result.duals))
        solver.close()
    }

    @Test
    fun `repair resource stop prevents further numerical retries`() {
        val b = LpBuilder()
        val x = b.addVar(0L, 2L)
        b.addRow(mapOf(x to 1L), Relation.GE, 1L)
        var builds = 0
        val solver = RevisedSimplex(
            b.build(Sense.MINIMIZE),
            workLimit = 100L,
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
        assertTrue(solver.lastWorkOps >= 100L)
        assertNull(solver.infeasibleRay)
        solver.close()
    }

    @Test
    fun `cancellation prevents an unscaled retry`() {
        val b = LpBuilder()
        val x = b.addVar(0L, 2L)
        b.addRow(mapOf(x to 1024L), Relation.GE, 1024L)
        var cancelled = false
        var builds = 0
        val solver = RevisedSimplex(
            b.build(Sense.MINIMIZE),
            cancellation = Cancellation { cancelled },
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
        val solver = RevisedSimplex(model)
        val first = assertNotNull(solver.solve())
        assertTrue(solver.rejectSingularBasis(model, first.basis))

        assertNull(solver.solve())

        assertNull(solver.continuationBasis(model))
        assertNull(solver.solvedExactState)
        assertNull(solver.infeasibleRay)
        solver.close()
    }
}
