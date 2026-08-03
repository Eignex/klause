package com.eignex.klause.solver

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import kotlin.test.Test
import kotlin.test.assertEquals

class ProblemTest {

    // x in [0, 10] with x <= 3: the root bake tightens the open upper bound to 3.
    private fun tighteningFactors(): List<Factor> = listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3))

    @Test
    fun `a raw problem keeps its declared domains unbaked`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = tighteningFactors(),
        )
        assertEquals(10, problem.intDomains[0].max, "a raw problem never folds the root bake")
    }

    @Test
    fun `bake folds the root deductions into the domains`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = tighteningFactors(),
        )
        assertEquals(3, problem.bake().intDomains[0].max, "bake carries the x <= 3 tightening")
    }
}
