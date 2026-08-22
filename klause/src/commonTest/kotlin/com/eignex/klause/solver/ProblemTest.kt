package com.eignex.klause.solver

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun `model bounds retain an open side behind the search clamp`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(-8, 8)),
            factors = emptyArray(),
            openIntLo = booleanArrayOf(true),
        )

        assertFalse(problem.intBounds.hasLower(0))
        assertTrue(problem.intBounds.hasUpper(0))
        assertEquals(8, problem.intBounds.upper(0))
    }

    @Test
    fun `model bounds represent an open range without a search domain`() {
        val openUpper = Bits(1).also { it.set(0) }
        val bounds = IntBounds.fromModelBounds(longArrayOf(3), longArrayOf(0), null, openUpper)

        assertTrue(bounds.hasLower(0))
        assertFalse(bounds.hasUpper(0))
        assertEquals(3, bounds.lower(0))
    }

    @Test
    fun `materializing a model keeps its open bounds separate from search domains`() {
        val openUpper = Bits(1).also { it.set(0) }
        val model = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(3), longArrayOf(0), null, openUpper),
            factors = emptyArray(),
        )

        val problem = model.materialize(arrayOf(IntDomain(3, 8)))

        assertEquals(8, problem.intDomains[0].max)
        assertFalse(problem.intBounds.hasUpper(0))
    }

    @Test
    fun `a problem spec classifies linear open columns before search materialization`() {
        val openUpper = Bits(1).also { it.set(0) }
        val spec = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, openUpper),
            factors = arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 4)),
        )

        assertTrue(spec.variablePartition().isTheoryEligible(0))
    }
}
