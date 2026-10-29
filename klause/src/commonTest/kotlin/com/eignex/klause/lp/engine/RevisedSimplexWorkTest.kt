package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisRepair
import com.eignex.klause.simplex.basis.BasisRepairControl
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RevisedSimplexWorkTest {
    @Test
    fun `a refused factor build should retain backend work without installed entries`() {
        RevisedSimplex(cover(), pricing = LpPricingOptions(basisEntryLimit = 1L)).use { solver ->
            val result = solver.solve()

            assertNull(result)
            assertTrue(solver.lastMetrics.basisBuildDeclines > 0L)
            assertTrue(solver.lastMetrics.basisBuildWork > 0L)
            assertEquals(0L, solver.lastMetrics.factorEntriesBuilt)
        }
    }

    @Test
    fun `a successful numerical solve should report installed backend entries`() {
        RevisedSimplex(cover()).use { solver ->
            assertNotNull(solver.solve())

            assertTrue(solver.lastMetrics.factorEntriesBuilt > 0L)
            assertTrue(solver.lastMetrics.basisBuildWork > 0L)
            assertEquals(0L, solver.lastMetrics.basisBuildUnmeasured)
        }
    }

    @Test
    fun `repair should use the retained invocation allowance and charge its completed overrun`() {
        var factorCalls = 0
        var repairAllowance: Long? = null
        val retainedLimit = 1000L
        RevisedSimplex(
            cover(),
            workLimit = 100000,
            basisSolverFactory = { matrix ->
                val delegate = KotlinBasisSolver(matrix)
                object : BasisSolver by delegate {
                    override fun refactorize(basicIndex: IntArray): Boolean {
                        factorCalls++
                        return false
                    }

                    override fun refactorizeRepairing(basicIndex: IntArray, control: BasisRepairControl): BasisRepair? {
                        repairAllowance = control.maxWork
                        control.charge(assertNotNull(control.maxWork) + 7)
                        return null
                    }
                }
            },
        ).use { solver ->
            val result = solver.resolveBounds(LpFloatAllowance(retainedLimit, 100))

            assertNull(result)
            assertTrue(assertNotNull(repairAllowance) in 1 until retainedLimit)
            assertEquals(retainedLimit + 7, solver.lastWorkOps)
            assertEquals(1, factorCalls)
        }
    }

    private fun cover(): LpModel {
        val b = LpBuilder()
        val x1 = b.addVar(0L, 10L, cost = 1L)
        val x2 = b.addVar(0L, 10L, cost = 1L)
        val x3 = b.addVar(0L, 10L, cost = 1L)
        val x4 = b.addVar(0L, 10L, cost = 1L)
        b.addRow(intArrayOf(x1, x2), longArrayOf(1L, 1L), Relation.GE, 3L)
        b.addRow(intArrayOf(x2, x3), longArrayOf(1L, 1L), Relation.GE, 4L)
        b.addRow(intArrayOf(x3, x4), longArrayOf(1L, 1L), Relation.GE, 5L)
        b.addRow(intArrayOf(x1, x4), longArrayOf(1L, 1L), Relation.GE, 2L)
        return b.build(Sense.MINIMIZE)
    }

    @Test
    fun `a work budget should stop the solve and still bound below`() {
        val full = RevisedSimplex(cover())
        val fullResult = assertNotNull(full.solve(null))
        assertTrue(full.lastWorkOps > 2L, "fixture must cost enough work to halve")

        val capped = RevisedSimplex(cover(), workLimit = full.lastWorkOps / 2)
        val cappedResult = assertNotNull(capped.solve(null))

        assertFalse(cappedResult.optimal)
        assertTrue(
            cappedResult.objective <= fullResult.objective + 1e-9,
            "a dual-feasible iterate bounds below: ${cappedResult.objective} vs ${fullResult.objective}",
        )
    }

    @Test
    fun `measuring degeneracy for the budget should not charge the budget`() {
        val plain = RevisedSimplex(cover())
        assertNotNull(plain.solve(null))

        val tracking = RevisedSimplex(cover(), trackDegeneracy = true)
        assertNotNull(tracking.solve(null))

        assertEquals(plain.lastWorkOps, tracking.lastWorkOps)
    }

    @Test
    fun `replays should charge deterministic work for the same pivot trace`() {
        val trace = replayTrace()

        assertEquals(trace, replayTrace())
        assertEquals(BackendContingencies(0, 0, 0, 0), trace.backendContingencies)
        assertTrue(trace.operations.pivots >= 2)
        assertTrue(trace.operations.updateLimitRefactorizations > 0)
        assertTrue(trace.workOps > 0L)
    }

    private fun replayTrace(): WorkTrace {
        val model = pivotingModel()
        val solver = RevisedSimplex(model, refactorUpdateLimit = 1)
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
            observedPivotTrace(metrics.pivots),
        )
    }

    private fun observedPivotTrace(pivots: Int): List<BasisState> = (1..pivots).map { limit ->
        val solver = RevisedSimplex(
            pivotingModel(),
            refactorUpdateLimit = 1,
            iterationLimit = limit,
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
