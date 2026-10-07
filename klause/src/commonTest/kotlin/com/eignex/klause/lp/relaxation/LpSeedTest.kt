package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpSeedTest {

    @Test
    fun `a seed over an open model lies at the relaxation's point inside the box`() {
        // x0 = 40 and x1 = x0 + 2 over columns open on both sides.
        val open = Bits(2).also {
            it.set(0)
            it.set(1)
        }
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(0, 0), open, open),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 40),
                Linear(intArrayOf(1, -1), intArrayOf(1, 0), LinearOp.EQ, 2),
            ),
        )
        val box = arrayOf(IntDomain(-1000, 1000), IntDomain(-1000, 1000))

        val seed = assertNotNull(model.lpSeed(box, Cancellation.Never))

        assertEquals(40L, seed.ints[0])
        assertEquals(42L, seed.ints[1])
    }

    @Test
    fun `a seed is drawn over a reified row on open columns`() {
        // b ⇔ x0 ≥ 3 with b true and x0 open: the reified row's big-M needs a range the source never states.
        val open = Bits(1).also { it.set(0) }
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), open, open),
            factors = arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.GE, 3),
                Clause(intArrayOf(Lit.make(0, true))),
            ),
        )

        val seed = assertNotNull(model.lpSeed(arrayOf(IntDomain(-100, 100)), Cancellation.Never))

        assertTrue(seed.ints[0] in -100L..100L)
    }
}
