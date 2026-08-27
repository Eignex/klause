package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals

class BakedProblemTest {

    @Test
    fun `bake folds the root deductions into the domains`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3)),
        )

        assertEquals(3, problem.bake().requireFiniteIntDomains()[0].max, "bake carries the x <= 3 tightening")
    }
}
