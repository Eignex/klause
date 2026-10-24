package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class PolynomialSourcePreparationTest {
    @Test
    fun `factor backed polynomial preparation should rebuild after removing a dependent real equality`() {
        val source = Problem(0, 0, emptyArray(), arrayOf<Factor>(
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(1.0, 1.0), LinearOp.EQ, 1.0),
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(1, 2), doubleArrayOf(1.0, 1.0), LinearOp.EQ, 2.0),
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 2), doubleArrayOf(1.0, -1.0), LinearOp.EQ, -1.0),
            RealProduct(0, 0, 1, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY),
        ), numRealVars = 3, realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY })
        val request = source.pipelineRequest()

        val prepared = request.prepareSource(PresolveConfig.parse("off,+dependent-rows"), null,
            Cancellation.Never, Cancellation.Never, null)
        val rebuilt = assertNotNull(request.afterSourcePreparation(prepared, Cancellation.Never))

        assertEquals(3, prepared.problem.factors.size)
        assertSame(source, prepared.source)
        assertSame(prepared.problem, rebuilt.preparedPlan.originalSource)
    }
}
