package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.HfactorBasisSolver
import com.eignex.koblas.SparseMatrix
import com.eignex.koblas.hfactor.BundledHfactor
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpOperationTraceTest {
    companion object {
        private lateinit var hfactor: BundledHfactor

        @BeforeClass
        @JvmStatic
        fun loadHfactor() {
            hfactor = BundledHfactor()
            check(hfactor.isAvailable) { hfactor.unavailableReason.orEmpty() }
        }
    }

    @Test
    fun `fixed backend replays charge deterministic work for the same pivot trace`() {
        val factories: List<((SparseMatrix) -> BasisSolver)?> = listOf(
            null,
            { matrix -> HfactorBasisSolver(matrix, hfactor) },
        )
        for (factory in factories) {
            val trace = replayTrace(factory)

            assertEquals(trace, replayTrace(factory))
            assertEquals(BackendContingencies(0, 0, 0, 0), trace.backendContingencies)
            assertTrue(trace.operations.pivots >= 2)
            assertTrue(trace.operations.updateLimitRefactorizations > 0)
            assertTrue(trace.workOps > 0L)
        }
    }

    private fun replayTrace(factory: ((SparseMatrix) -> BasisSolver)?): WorkTrace {
        val model = pivotingModel()
        val solver = RevisedSimplex(model, refactorUpdateLimit = 1, basisSolverFactory = factory)
        val result = solver.use { assertNotNull(it.solve()) }
        assertTrue(result.optimal)
        val x = result.primal
        assertTrue(x.all { it >= -1e-9 && it <= 10.0 + 1e-9 })
        assertTrue(x[0] + x[1] >= 3.0 - 1e-9)
        assertTrue(x[1] + x[2] >= 4.0 - 1e-9)
        assertTrue(x[2] + x[3] >= 5.0 - 1e-9)
        assertTrue(x[0] + x[3] >= 2.0 - 1e-9)
        assertEquals(8.0, x.sum(), 1e-9)
        assertEquals(8L, integerDualLowerBoundCeil(model, result.duals))
        val metrics = solver.lastMetrics
        return WorkTrace(
            metrics.prescribedOperations(),
            metrics.backendContingencies(),
            metrics.workOps,
            result.basis.state(),
            observedPivotTrace(metrics.pivots, factory),
        )
    }

    private fun observedPivotTrace(pivots: Int, factory: ((SparseMatrix) -> BasisSolver)?): List<BasisState> =
        (1..pivots).map { limit ->
            val solver = RevisedSimplex(
                pivotingModel(),
                refactorUpdateLimit = 1,
                iterationLimit = limit,
                basisSolverFactory = factory,
            )
            val result = solver.use { assertNotNull(it.solve()) }
            assertEquals(limit, result.pivots)
            result.basis.state()
        }

    private fun Basis.state() = BasisState(basicVars.toList(), status.toList())

    private fun pivotingModel(): LpModel = LpBuilder().apply {
        val x1 = addVar(0L, 10L, cost = 1L)
        val x2 = addVar(0L, 10L, cost = 1L)
        val x3 = addVar(0L, 10L, cost = 1L)
        val x4 = addVar(0L, 10L, cost = 1L)
        addRow(intArrayOf(x1, x2), longArrayOf(1L, 1L), Relation.GE, 3L)
        addRow(intArrayOf(x2, x3), longArrayOf(1L, 1L), Relation.GE, 4L)
        addRow(intArrayOf(x3, x4), longArrayOf(1L, 1L), Relation.GE, 5L)
        addRow(intArrayOf(x1, x4), longArrayOf(1L, 1L), Relation.GE, 2L)
    }.build(Sense.MINIMIZE)

    private fun LpSolveMetrics.prescribedOperations() = PrescribedOperations(
        pivots,
        initialRefactorizations,
        warmStartRefactorizations,
        updateLimitRefactorizations,
        reconcileRecoveryRefactorizations,
        primalRefactorizations,
    )

    private fun LpSolveMetrics.backendContingencies() = BackendContingencies(
        singularRefactorizations,
        smallPivotBails,
        singularRecoveryRefactorizations,
        backendRequestedRefactorizations,
    )

    private data class PrescribedOperations(
        val pivots: Int,
        val initialRefactorizations: Int,
        val warmStartRefactorizations: Int,
        val updateLimitRefactorizations: Int,
        val reconcileRecoveryRefactorizations: Int,
        val primalRefactorizations: Int,
    )

    private data class BackendContingencies(
        val singularRefactorizations: Int,
        val smallPivotBails: Int,
        val singularRecoveryRefactorizations: Int,
        val backendRequestedRefactorizations: Int,
    )

    private data class WorkTrace(
        val operations: PrescribedOperations,
        val backendContingencies: BackendContingencies,
        val workOps: Long,
        val finalBasis: BasisState,
        val pivotTrace: List<BasisState>,
    )

    private data class BasisState(val basicVars: List<Int>, val status: List<VarStatus>)
}
