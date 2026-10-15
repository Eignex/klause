package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RootFixedRowsTest {
    private fun unit(variable: Int, positive: Boolean) = Clause(intArrayOf(Lit.make(variable, positive)))

    private fun rows(vararg f: Factor) = rootFixedReifiedRows(f.toList())

    @Test
    fun `a literal fixed false should yield the integer negation`() {
        val r = rows(unit(0, false), ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 5))
        assertEquals(1, r.size)
        assertEquals(LinearOp.LE, r[0].op)
        assertEquals(-6L, checkNotNull(r[0].integerConstants).bound)
        assertTrue(checkNotNull(r[0].integerConstants).coeffs.all { it == -1L })
    }

    @Test
    fun `a negated equality should yield no row`() {
        assertEquals(0, rows(unit(0, false), ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 5)).size)
    }

    @Test
    fun `a literal fixed both ways should yield no row`() {
        val r = rows(
            unit(0, true),
            unit(0, false),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 5),
        )
        assertEquals(0, r.size)
    }

    @Test
    fun `a negation beyond long range should retain its exact bound`() {
        val r = rows(unit(0, false), ReifiedLinear(0, longArrayOf(1L), intArrayOf(0), LinearOp.LE, Long.MAX_VALUE))
        assertEquals(
            -bigIntOf(Long.MAX_VALUE) -
                BIG_ONE,
            checkNotNull(r.single().integralConstants).exactBound,
        )
    }

    @Test
    fun `canonicalizing a minimum long lower bound should preserve its exact value`() {
        val result = rows(
            unit(0, true),
            ReifiedLinear(0, longArrayOf(1), intArrayOf(0), LinearOp.GE, Long.MIN_VALUE),
        )

        assertEquals(
            -bigIntOf(Long.MIN_VALUE),
            checkNotNull(result.single().wideConstants).bound,
        )
    }
}
