package com.eignex.klause.theory.lia

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.theory.TheoryCheck
import com.eignex.klause.theory.TheoryContext
import com.eignex.klause.util.Bits
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * How often the General LIA sweeps look at the budget. The work they amortise is `BigInteger` arithmetic
 * over the witness box's endpoints, so the stride has to shrink as that box widens — a fixed one either
 * overshoots the budget on a wide box or costs more than it guards on a narrow one.
 */
class PollStrideTest {

    @Test
    fun `a wide row is skipped when shared bounds fix a satisfying witness`() {
        val wide = BigInteger.ONE shl (MAX_LIA_PROPAGATION_BITS + 1)
        val model = ProblemSpec(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(0, 0),
                longArrayOf(0, 0),
                Bits(2).also { bits ->
                    bits.set(0)
                    bits.set(1)
                },
                Bits(2).also { bits ->
                    bits.set(0)
                    bits.set(1)
                },
            ),
            factors = arrayOf(
                Linear(intArrayOf(0, 1), arrayOf(wide, BigInteger.ONE), LinearOp.EQ, BigInteger.ZERO),
            ),
        )

        val result = GeneralLiaSolver(model).check(
            BooleanArray(1),
            object : TheoryContext {
                override fun consumeCheck(): Boolean = true

                override fun cancelled(): Boolean = false

                override fun intLowerBound(variable: Int): Long = 0

                override fun intUpperBound(variable: Int): Long = 0
            },
        )

        assertIs<TheoryCheck.Sat<GeneralLiaAssignment>>(result)
    }

    @Test
    fun `a narrow box polls at the coarsest stride`() {
        assertEquals(256, pollStrideFor(1))
        assertEquals(256, pollStrideFor(64))
    }

    @Test
    fun `a box too wide to walk polls on every factor`() {
        // The bofill-scheduling instance behind #1578's residue carries a 413454-bit box.
        assertEquals(1, pollStrideFor(413_454))
    }

    @Test
    fun `the stride never grows as the box widens`() {
        var previous = Int.MAX_VALUE
        for (bits in listOf(1, 64, 256, 1_024, 8_192, 65_536, 413_454)) {
            val stride = pollStrideFor(bits)
            assertTrue(stride <= previous, "stride grew at $bits bits: $previous then $stride")
            assertTrue(stride >= 1, "stride must stay positive, got $stride at $bits bits")
            previous = stride
        }
    }
}
