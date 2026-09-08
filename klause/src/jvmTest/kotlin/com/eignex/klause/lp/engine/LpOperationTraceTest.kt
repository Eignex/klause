package com.eignex.klause.lp.engine

import com.eignex.koblas.F64Capabilities
import com.eignex.koblas.F64ContextBuilder
import com.eignex.koblas.backendNamed
import com.eignex.koblas.discoverBackends
import com.eignex.koblas.installBackends
import com.eignex.koblas.koblas
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpOperationTraceTest {

    @Test
    fun `identical prescribed backend traces charge identical work`() = synchronized(backendLock) {
        ensureKoblasBackends()
        discoverBackends()
        val automatic = koblas
        val portable = F64ContextBuilder().resolve()
        val hfactor = assertNotNull(
            backendNamed("hfactor-bundled", F64Capabilities.basisSolvers)
                ?: backendNamed("hfactor", F64Capabilities.basisSolvers),
            "the JVM test runtime must provide HFactor",
        )
        try {
            installBackends(portable)
            val portableTrace = replayTrace()
            installBackends(automatic.with(basisSolvers = hfactor))
            val hfactorTrace = replayTrace()

            assertEquals(
                portableTrace.operations,
                hfactorTrace.operations,
                "fixtures must prescribe one operation trace",
            )
            assertContentEquals(portableTrace.finalBasis.basicVars, hfactorTrace.finalBasis.basicVars)
            assertContentEquals(portableTrace.finalBasis.status, hfactorTrace.finalBasis.status)
            assertEquals(portableTrace.workOps, hfactorTrace.workOps)
            assertTrue(portableTrace.workOps > 0L)
        } finally {
            installBackends(null)
        }
    }

    private fun replayTrace(): WorkTrace {
        val solver = RevisedSimplex(alreadyOptimalModel())
        val result = solver.use { assertNotNull(it.solve()) }
        val metrics = solver.lastMetrics
        val operations = PrescribedSolve(metrics.pivots, metrics.refactorizationTrace())
        assertEquals(PrescribedSolve(0, listOf(1, 0, 0, 0, 0, 0, 0)), operations)
        return WorkTrace(
            operations,
            metrics.workOps,
            result.basis,
        )
    }

    private fun alreadyOptimalModel(): LpModel = LpBuilder().apply {
        val x = addVar(0L, 5L, cost = 1L)
        val y = addVar(0L, 5L, cost = 2L)
        addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.LE, 7L)
        addRow(intArrayOf(x, y), longArrayOf(2L, 1L), Relation.LE, 9L)
    }.build(Sense.MINIMIZE)

    private fun LpSolveMetrics.refactorizationTrace(): List<Int> = listOf(
        initialRefactorizations,
        warmStartRefactorizations,
        singularRecoveryRefactorizations,
        updateLimitRefactorizations,
        backendRequestedRefactorizations,
        reconcileRecoveryRefactorizations,
        primalRefactorizations,
    )

    private data class PrescribedSolve(val pivots: Int, val refactorizations: List<Int>)

    private data class WorkTrace(val operations: PrescribedSolve, val workOps: Long, val finalBasis: Basis)

    private companion object {
        val backendLock = Any()
    }
}
