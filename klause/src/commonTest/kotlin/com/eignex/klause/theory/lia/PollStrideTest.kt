package com.eignex.klause.theory.lia

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.solver.ProblemPipeline
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.generalLiaWitnessBound
import com.eignex.klause.solver.pipeline.OpenTheoryEngine
import com.eignex.klause.solver.pipeline.OpenTheoryResult
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
    fun `a witness box beyond the arithmetic limit declines General LIA search`() {
        // This equality makes the small-model theorem produce a 21k-bit box. Propagating it would divide
        // products of that box and the 4k-bit coefficient, so decline before the first endpoint division.
        val wide = BigInteger.parseString("1" + "0".repeat(1_300))
        val model = ProblemSpec(
            // An unused Boolean keeps this model off OpenTheoryEngine's cheap cube witness path, so the
            // test reaches the General LIA component that decides whether the box is safe to materialise.
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

        assertTrue(checkNotNull(model.generalLiaWitnessBound()).bitLength() > MAX_GENERAL_LIA_WITNESS_BITS)
        assertIs<OpenTheoryResult.Unknown>(OpenTheoryEngine(model, ProblemPipeline.GENERAL_LIA).solve())
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
