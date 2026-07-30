package com.eignex.klause.solver

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import kotlin.test.Test
import kotlin.test.assertEquals

class ProblemTest {

    // x in [0, 10] with x <= 3: the root bake tightens the open upper bound to 3.
    private fun tighteningFactors(): List<Factor> = listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3))

    @Test
    fun `bakedDomains folds the root bake for a deferBake problem while intDomains stays raw`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = tighteningFactors(),
            deferBake = true,
        )
        assertEquals(10, problem.intDomains[0].max, "deferBake leaves the declared domain raw")
        assertEquals(3, problem.bakedDomains[0].max, "bakedDomains carries the x <= 3 tightening")
    }

    @Test
    fun `bakedDomains is the folded view for an eagerly baked problem too`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = tighteningFactors(),
        )
        assertEquals(3, problem.intDomains[0].max, "eager construction folds in place")
        assertEquals(3, problem.bakedDomains[0].max, "bakedDomains carries the folded upper bound")
    }
}
