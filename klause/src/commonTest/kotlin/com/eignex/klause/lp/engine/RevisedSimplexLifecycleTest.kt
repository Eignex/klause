package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisOperationWork
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class RevisedSimplexLifecycleTest {
    @Test
    fun `a failed basis close retires the owner once and retains its work`() {
        val model = LpBuilder().apply {
            addVar(0L, 3L, cost = 1L)
            addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 2L)
        }.build(Sense.MINIMIZE)
        val cleanup = IllegalStateException("cleanup")
        var closes = 0
        val solver = RevisedSimplex(model, basisSolverFactory = { matrix ->
            val delegate = KotlinBasisSolver(matrix)
            object : BasisSolver by delegate {
                override fun close() {
                    closes++
                    delegate.close()
                    throw cleanup
                }
            }
        })
        assertNotNull(solver.solve())
        val work = assertNotNull(solver.basisLifecycleWork)

        val failure = assertFailsWith<IllegalStateException> { solver.close() }
        solver.close()

        assertSame(cleanup, failure)
        assertEquals(1, closes)
        assertEquals(work, solver.basisLifecycleWork)
    }

    @Test
    fun `an unreadable retiring basis ledger keeps incomplete accounting`() {
        val model = LpBuilder().apply {
            addVar(0L, 3L, cost = 1L)
            addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 2L)
        }.build(Sense.MINIMIZE)
        var unreadable = false
        var closes = 0
        val solver = RevisedSimplex(model, basisSolverFactory = { matrix ->
            val delegate = KotlinBasisSolver(matrix)
            object : BasisSolver by delegate {
                override val basisOperationWork: BasisOperationWork?
                    get() = if (unreadable) error("ledger") else delegate.basisOperationWork
                override fun close() {
                    closes++
                    delegate.close()
                }
            }
        })
        assertNotNull(solver.solve())
        unreadable = true

        solver.close()
        solver.close()

        assertEquals(1, closes)
        assertFalse(assertNotNull(solver.basisLifecycleWork).complete)
    }
}
