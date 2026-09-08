package com.eignex.klause.lp.engine

import com.eignex.koblas.Capabilities
import com.eignex.koblas.ContextBuilder
import com.eignex.koblas.backendNamed
import com.eignex.koblas.installBackends
import com.eignex.koblas.koblas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpOperationTraceTest {

    @Test
    fun `fixed backend replays charge deterministic work for the same pivot trace`() {
        ensureKoblasBackends()
        val original = koblas
        val portable = ContextBuilder().resolve()
        val hfactor = assertNotNull(
            backendNamed("hfactor-bundled", Capabilities.basisSolvers)
                ?: backendNamed("hfactor", Capabilities.basisSolvers),
            "the JVM test runtime must provide HFactor",
        )
        assertTrue(portable.basisSolvers.isPortable)
        assertFalse(hfactor.isPortable)
        assertTrue(portable.basisSolvers.name != hfactor.name)
        try {
            installBackends(portable)
            val portableTrace = replayTrace()
            assertEquals(portableTrace, replayTrace(), "portable replay must be deterministic")
            installBackends(original.with(basisSolvers = hfactor))
            val hfactorTrace = replayTrace()
            assertEquals(hfactorTrace, replayTrace(), "HFactor replay must be deterministic")
            val noBackendContingencies = BackendContingencies(0, 0, 0, 0)

            assertEquals(noBackendContingencies, portableTrace.backendContingencies)
            assertEquals(noBackendContingencies, hfactorTrace.backendContingencies)
            assertTrue(portableTrace.operations.pivots >= 2)
            assertTrue(hfactorTrace.operations.pivots >= 2)
            assertTrue(portableTrace.operations.updateLimitRefactorizations > 0)
            assertTrue(hfactorTrace.operations.updateLimitRefactorizations > 0)
            assertTrue(portableTrace.workOps > 0L)
            assertTrue(hfactorTrace.workOps > 0L)
        } finally {
            installBackends(original)
        }
    }

    private fun replayTrace(): WorkTrace {
        val solver = RevisedSimplex(pivotingModel(), refactorUpdateLimit = 1)
        val result = solver.use { assertNotNull(it.solve()) }
        val metrics = solver.lastMetrics
        return WorkTrace(
            metrics.prescribedOperations(),
            metrics.backendContingencies(),
            metrics.workOps,
            result.basis.state(),
            observedPivotTrace(metrics.pivots),
        )
    }

    private fun observedPivotTrace(pivots: Int): List<BasisState> = (1..pivots).map { limit ->
        val solver = RevisedSimplex(pivotingModel(), refactorUpdateLimit = 1, iterationLimit = limit)
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
