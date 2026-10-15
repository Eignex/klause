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
    fun `late cancellation after unscaled fallback should withhold all publication`() {
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

        assertEquals(LpFloatTermination.CANCELLED, solver.lastTermination)
        assertNull(solver.infeasibleRay)
        assertNull(solver.solvedExactState)
        assertTrue(solver.gomoryCuts(1).isEmpty())
        solver.close()
    }

    @Test
    fun `unscaled fallback should share the iteration allowance`() {
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
        assertEquals(1, solver.scalingMetrics.fallbacks)
        assertEquals(1, solver.lastPivots)
        assertEquals(LpFloatTermination.PIVOTS, solver.lastTermination)
        solver.close()
    }

    @Test
    fun `exact singularity should reject the numerical heading and permit a different source basis`() {
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
        val statuses = arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.FIXED, VarStatus.FIXED)
        val proposal = assertNotNull(solver.solve(Basis(heading, statuses)))
        assertEquals(ExactBasisDecline.SINGULAR, verifyExactBasis(model, proposal.basis).metrics.decline)
        assertTrue(solver.rejectSingularBasis(model, proposal.basis))

        val recovered = assertNotNull(solver.resolveBounds())

        assertFalse(recovered.basis.basicVars.contentEquals(heading))
        assertNotNull(verifyExactBasis(model, recovered.basis).witness)
        solver.close()
    }

    @Test
    fun `unrecoverable arithmetic failure should report numerical termination`() {
        val builder = LpBuilder()
        val column = builder.addVar(0L, 2L, cost = 1L)
        builder.addRow(mapOf(column to 1024L), Relation.GE, 1024L)
        RevisedSimplex(
            builder.build(Sense.MINIMIZE),
            basisSolverFactory = { matrix ->
                val delegate = KotlinBasisSolver(matrix)
                object : BasisSolver by delegate {
                    override fun ftran(x: IndexedVector, expectedDensity: Double): Unit =
                        throw BasisArithmeticException("numerical control")
                }
            },
        ).use { solver ->
            assertNull(solver.solve())

            assertEquals(LpFloatTermination.NUMERICAL, solver.lastTermination)
            assertNull(solver.infeasibleRay)
        }
    }

    @Test
    fun `scale fallback should not reaccept an exactly rejected heading`() {
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
    fun `a repair resource stop should prevent further numerical retries`() {
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
    fun `an exactly rejected heading should not be reexported after a cold retry`() {
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
