package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BakedProblemTest {

    private fun holey(): IntDomain = IntDomain(0, 6).excludeValue(2).excludeValue(4)

    @Test
    fun `bake folds the root deductions into the domains`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3)),
        )

        assertEquals(3, problem.bake().rootIntDomain(0).max, "bake carries the x <= 3 tightening")
    }

    @Test
    fun `the finite surface reports the folded domain of each column`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 10)),
            factors = listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3)),
        )

        val baked = problem.bake()

        assertEquals(IntDomain(0, 3), baked.rootIntDomain(0))
        assertEquals(IntDomain(0, 10), baked.rootIntDomain(1))
    }

    @Test
    fun `a non-contiguous declaration reaches the finite projection with its holes`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(holey()),
            factors = arrayOf<Factor>(),
        )

        assertEquals(holey(), problem.bake().rootIntDomain(0))
    }

    @Test
    fun `the finite domains hand back a copy rather than the array the fold owns`() {
        val baked = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = arrayOf<Factor>(),
        ).bake()

        baked.rootIntDomains()[0] = IntDomain(7, 7)

        assertEquals(IntDomain(0, 10), baked.rootIntDomain(0))
    }

    @Test
    fun `baking a model that declares bounds alone materializes its range`() {
        val problem = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(2), longArrayOf(9), null, null),
            factors = emptyArray(),
        )

        assertEquals(IntDomain(2, 9), problem.bake().rootIntDomain(0))
    }

    @Test
    fun `baking a column with an open side is refused rather than boxed`() {
        val problem = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(0),
                longArrayOf(0),
                null,
                Bits(1).also { it.set(0) },
            ),
            factors = emptyArray(),
        )

        assertFailsWith<IllegalArgumentException> { problem.bake() }
    }
}
