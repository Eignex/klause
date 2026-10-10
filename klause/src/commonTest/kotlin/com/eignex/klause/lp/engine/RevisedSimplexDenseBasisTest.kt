package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RevisedSimplexDenseBasisTest {
    @Test
    fun `dense and sparse warm bases certify the same attained optimum`() {
        for (dimension in listOf(6, 7)) {
            for (denseLimit in listOf(0, 6)) {
                val builder = LpBuilder()
                repeat(dimension) { builder.addVar(0L, 2L, cost = 1L) }
                repeat(dimension) { row ->
                    builder.addRow(
                        IntArray(dimension) { it }, LongArray(dimension) { if (it == row) 2L else 1L },
                        Relation.EQ, (dimension + 1).toLong(),
                    )
                }
                val model = builder.build(Sense.MINIMIZE)
                val warm = Basis(IntArray(dimension) { it }, Array(model.numVars) {
                    if (it < dimension) VarStatus.BASIC else VarStatus.FIXED
                })
                lateinit var factors: KotlinBasisSolver
                RevisedSimplex(model, basisSolverFactory = { matrix ->
                    KotlinBasisSolver(matrix, denseDimensionLimit = denseLimit).also { factors = it }
                }).use { solver ->
                    val result = assertNotNull(solver.solve(warm))
                    val certified = certifyLpResult(model, solver, result)

                    assertEquals(LpVerdict.ATTAINED_OPTIMUM, certified.verdict)
                    assertEquals(BigFraction.ofLong(dimension.toLong()), certified.lowerBound)
                    assertNotNull(certified.witness)
                    assertTrue(factors.basisOperationWork.complete)
                    assertEquals(if (dimension <= denseLimit) 1L else 0L,
                        assertNotNull(factors.basisWork.build).denseAttempts)
                }
            }
        }
    }

    @Test
    fun `cancellation after dense factorization withholds numerical and exact publication`() {
        val builder = LpBuilder()
        repeat(2) { builder.addVar(0L, 2L, cost = 1L) }
        builder.addRow(intArrayOf(0, 1), longArrayOf(2, 1), Relation.EQ, 3L)
        builder.addRow(intArrayOf(0, 1), longArrayOf(1, 2), Relation.EQ, 3L)
        val model = builder.build(Sense.MINIMIZE)
        var cancelled = false
        var denseAttempts = 0L
        val token = Cancellation { cancelled }
        RevisedSimplex(model, cancellation = token, basisSolverFactory = { matrix ->
            val delegate = KotlinBasisSolver(matrix)
            object : BasisSolver by delegate {
                override fun refactorize(basicIndex: IntArray): Boolean {
                    val result = delegate.refactorize(basicIndex)
                    denseAttempts = assertNotNull(delegate.basisWork.build).denseAttempts
                    cancelled = true
                    return result
                }
            }
        }).use { solver ->
            val warm = Basis(intArrayOf(0, 1), arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.FIXED, VarStatus.FIXED))
            val result = solver.solve(warm)
            val certified = certifyLpResult(model, solver, result, cancellation = token)

            assertEquals(1L, denseAttempts)
            assertNull(result)
            assertEquals(LpVerdict.INDETERMINATE, certified.verdict)
            assertNull(certified.witness)
            assertNull(certified.lowerBound)
        }
    }
}
