package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OpenWitnessTest {

    // x0 ≥ 0 declared, open above, and x0 + x1 = 10 with x1 open on both sides.
    private val model = Problem(
        numBoolVars = 0,
        intBounds = IntBounds.fromModelBounds(
            longArrayOf(0, 0),
            longArrayOf(0, 0),
            Bits(2).also { it.set(1) },
            Bits(2).also {
                it.set(0)
                it.set(1)
            },
        ),
        factors = arrayOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 10)),
    )

    private fun sample(x0: Long, x1: Long) = Sample(BooleanArray(0), longArrayOf(x0, x1))

    @Test
    fun `a point satisfying every factor is a witness`() {
        assertNull(refuteOpenWitness(model, sample(3, 7)))
    }

    @Test
    fun `a point violating a factor is refuted`() {
        assertNotNull(refuteOpenWitness(model, sample(3, 8)))
    }

    @Test
    fun `a point outside a declared side is refuted`() {
        assertNotNull(refuteOpenWitness(model, sample(-1, 11)))
    }
}
