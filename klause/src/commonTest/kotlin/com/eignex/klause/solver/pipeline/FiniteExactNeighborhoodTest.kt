package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FiniteExactNeighborhoodTest {
    @Test
    fun `pinned mixed products retain exact source checks during real repairs`() {
        val source = Problem(0, 1, arrayOf(IntDomain(3, 3)),
            arrayOf(RealProduct(0, 0, 1, 0.0, 1.0), Linear(intArrayOf(), doubleArrayOf(),
                intArrayOf(0), doubleArrayOf(3.0), LinearOp.EQ, 1.0)),
            numRealVars = 2, realLower = doubleArrayOf(0.0, 0.0), realUpper = doubleArrayOf(1.0, 3.0)).bake()
        val point = assertNotNull(source.fixedDiscreteSample())

        for (adaptive in listOf(false, true)) {
            val worker = finiteExactNeighborhoodWorker(source, point, null, null, adaptive, 17, 0)
            val result = worker.use { it.solve(Cancellation.Never) }

            val witness = assertIs<SolveResult.Sat>(result).assignment
            assertEquals("1/3", witness.exactReals?.get(0).toString())
            assertEquals("1", witness.exactReals?.get(1).toString())
            assertTrue(result.stats.ls.completionsRefuted.sum > 0)
        }
    }
}
