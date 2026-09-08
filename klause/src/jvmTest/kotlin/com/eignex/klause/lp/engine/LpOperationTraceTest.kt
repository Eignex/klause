package com.eignex.klause.lp.engine

import com.eignex.koblas.F64Capabilities
import com.eignex.koblas.F64ContextBuilder
import com.eignex.koblas.backendNamed
import com.eignex.koblas.installBackends
import com.eignex.koblas.koblas
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpOperationTraceTest {

    @Test
    fun `identical prescribed backend traces charge identical work`() {
        ensureKoblasBackends()
        val original = koblas
        val portable = F64ContextBuilder().resolve()
        val hfactor = assertNotNull(
            backendNamed("hfactor-bundled", F64Capabilities.basisSolvers)
                ?: backendNamed("hfactor", F64Capabilities.basisSolvers),
            "the JVM test runtime must provide HFactor",
        )
        assertTrue(portable.basisSolvers.isPortable)
        assertFalse(hfactor.isPortable)
        assertTrue(portable.basisSolvers.name != hfactor.name)
        try {
            installBackends(portable)
            val portableTrace = replayTrace()
            installBackends(original.with(basisSolvers = hfactor))
            val hfactorTrace = replayTrace()
            val noBackendContingencies = BackendContingencies(0, 0, 0, 0)

            assertEquals(
                portableTrace.operations,
                hfactorTrace.operations,
                "fixtures must prescribe one operation trace",
            )
            assertEquals(noBackendContingencies, portableTrace.backendContingencies)
            assertEquals(noBackendContingencies, hfactorTrace.backendContingencies)
            assertEquals(portableTrace.finalBasis.basicVars.toSet(), hfactorTrace.finalBasis.basicVars.toSet())
            assertContentEquals(portableTrace.finalBasis.status, hfactorTrace.finalBasis.status)
            assertEquals(portableTrace.workOps, hfactorTrace.workOps)
            assertTrue(portableTrace.operations.pivots >= 2)
            assertTrue(portableTrace.operations.updateLimitRefactorizations > 0)
            assertTrue(portableTrace.workOps > 0L)
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
            result.basis,
        )
    }

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
        val finalBasis: Basis,
    )
}
