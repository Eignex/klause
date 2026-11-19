package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.times
import com.eignex.util.numbers.toLongExact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PbAccumulatorTest {
    @Test
    fun `negative literal saturation should preserve the positive degree`() {
        val value = acc { loadPb(longArrayOf(100, 1), intArrayOf(Lit.make(0, false), Lit.make(1, true)), 2) }

        value.saturate()

        assertEquals(bigIntOf(-2), value.coefOf(0))
        assertEquals(bigIntOf(2), value.positiveDegree())
    }

    @Test
    fun `division should round the degree before reducing negative coefficients`() {
        val value = acc { loadPb(longArrayOf(3, 3), intArrayOf(Lit.make(0, false), Lit.make(1, true)), 4) }

        value.divideRoundUp(3)

        assertEquals(bigIntOf(-1), value.coefOf(0))
        assertEquals(bigIntOf(2), value.positiveDegree())
    }

    @Test
    fun `gcd reduction should retain a mixed polarity integer consequence`() {
        val value = acc { loadPb(longArrayOf(2, 4), intArrayOf(Lit.make(0, false), Lit.make(1, true)), 3) }

        value.normalizeByGcd()

        assertEquals(bigIntOf(-1), value.coefOf(0))
        assertEquals(bigIntOf(2), value.coefOf(1))
        assertEquals(bigIntOf(2), value.positiveDegree())
    }

    @Test
    fun `an activity beyond signed long should remain available for saturation`() {
        val a = acc { loadPb(longArrayOf(Long.MAX_VALUE, 1), intArrayOf(Lit.make(0, true), Lit.make(1, true)), 1) }

        assertTrue(a.hasSafeActivity())
    }

    private fun acc(block: PbAccumulator.() -> Unit) = PbAccumulator().apply(block)

    @Test
    fun `generalized resolution should cancel the pivot like clause resolution`() {
        // (x0 ∨ x1) resolve (¬x0 ∨ x2) on x0  ⇒  (x1 ∨ x2)
        val c = acc { loadClause(intArrayOf(Lit.make(0, true), Lit.make(1, true))) }
        val r = acc { loadClause(intArrayOf(Lit.make(0, false), Lit.make(2, true))) }
        assertTrue(c.addScaled(r, mulSelf = 1, mulOther = 1))
        assertEquals(0L, c.coefOf(0).toLongExact())
        assertEquals(1L, c.coefOf(1).toLongExact())
        assertEquals(1L, c.coefOf(2).toLongExact())
        assertEquals(1L, c.positiveDegree().toLongExact())
    }

    @Test
    fun `resolution should scale unequal pivot coefficients to cancel`() {
        // C: 3·x0 + 2·x1 ≥ 3 ; R: 2·¬x0 + 5·x2 ≥ 4. Cancel x0 with mulSelf=2, mulOther=3.
        val c = acc { loadPb(longArrayOf(3, 2), intArrayOf(Lit.make(0, true), Lit.make(1, true)), geBound = 3) }
        val r = acc { loadPb(longArrayOf(2, 5), intArrayOf(Lit.make(0, false), Lit.make(2, true)), geBound = 4) }
        assertTrue(c.addScaled(r, mulSelf = 2, mulOther = 3))
        assertEquals(0L, c.coefOf(0).toLongExact())
        assertEquals(4L, c.coefOf(1).toLongExact()) // 2·2
        assertEquals(15L, c.coefOf(2).toLongExact()) // 3·5
    }

    @Test
    fun `saturation should cap coefficients at the degree without changing the solution set`() {
        // 5·x0 ≥ 3  ⇔  x0=1. After saturation: 3·x0 ≥ 3  ⇔  x0=1.
        val a = acc { loadPb(longArrayOf(5), intArrayOf(Lit.make(0, true)), geBound = 3) }
        a.saturate()
        assertEquals(3L, a.coefOf(0).toLongExact())
        assertEquals(3L, a.positiveDegree().toLongExact())
    }

    @Test
    fun `divide-and-round-up should yield a valid cutting plane`() {
        // 3·x0 + 3·x1 ≥ 4  ⇔  x0+x1 ≥ 2. Divide by 3, round up: x0 + x1 ≥ 2.
        val a = acc { loadPb(longArrayOf(3, 3), intArrayOf(Lit.make(0, true), Lit.make(1, true)), geBound = 4) }
        a.divideRoundUp(3)
        assertEquals(1L, a.coefOf(0).toLongExact())
        assertEquals(1L, a.coefOf(1).toLongExact())
        assertEquals(2L, a.positiveDegree().toLongExact())
    }

    @Test
    fun `gcd normalization should divide through and round the degree up`() {
        // 2·x0 + 4·x1 ≥ 4  ⇔  x0 + 2·x1 ≥ 2.
        val a = acc { loadPb(longArrayOf(2, 4), intArrayOf(Lit.make(0, true), Lit.make(1, true)), geBound = 4) }
        a.normalizeByGcd()
        assertEquals(1L, a.coefOf(0).toLongExact())
        assertEquals(2L, a.coefOf(1).toLongExact())
        assertEquals(2L, a.positiveDegree().toLongExact())
    }

    @Test
    fun `scaled coefficients should remain exact beyond signed long`() {
        val a = acc { loadPb(longArrayOf(Long.MAX_VALUE / 2), intArrayOf(Lit.make(0, true)), geBound = 1) }
        val b = acc { loadPb(longArrayOf(Long.MAX_VALUE / 2), intArrayOf(Lit.make(0, false)), geBound = 1) }
        assertTrue(a.addScaled(b, mulSelf = 4, mulOther = 1))
        assertEquals(bigIntOf(Long.MAX_VALUE / 2) * bigIntOf(3), a.coefOf(0))
    }
}
