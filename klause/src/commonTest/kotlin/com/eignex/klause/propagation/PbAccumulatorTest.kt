package com.eignex.klause.propagation

import com.eignex.klause.solver.Lit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cutting-planes arithmetic for [PbAccumulator]: generalized resolution, saturation, rounding, and
 *  overflow guards — the sound-derivation core of PB conflict learning (#1119 Phase 3). */
class PbAccumulatorTest {

    private fun acc(block: PbAccumulator.() -> Unit) = PbAccumulator().apply(block)

    @Test
    fun `a clause loads as sum of literals ge one`() {
        val a = acc { loadClause(intArrayOf(Lit.make(0, true), Lit.make(1, true))) }
        assertEquals(1L, a.coefOf(0))
        assertEquals(1L, a.coefOf(1))
        assertEquals(1L, a.positiveDegree())
    }

    @Test
    fun `a negative literal folds onto its variable and shifts the degree`() {
        // ¬x0 ∨ x1  ==  1·¬x0 + 1·x1 ≥ 1  ==  −x0 + x1 ≥ 0
        val a = acc { loadPb(longArrayOf(1, 1), intArrayOf(Lit.make(0, false), Lit.make(1, true)), geBound = 1) }
        assertEquals(-1L, a.coefOf(0))
        assertEquals(1L, a.coefOf(1))
        assertEquals(0L, a.rhs)
        assertEquals(1L, a.positiveDegree()) // rhs + |neg| = 0 + 1
    }

    @Test
    fun `generalized resolution cancels the pivot like clause resolution`() {
        // (x0 ∨ x1) resolve (¬x0 ∨ x2) on x0  ⇒  (x1 ∨ x2)
        val c = acc { loadClause(intArrayOf(Lit.make(0, true), Lit.make(1, true))) }
        val r = acc { loadClause(intArrayOf(Lit.make(0, false), Lit.make(2, true))) }
        assertTrue(c.addScaled(r, mulSelf = 1, mulOther = 1))
        assertEquals(0L, c.coefOf(0), "pivot cancels")
        assertEquals(1L, c.coefOf(1))
        assertEquals(1L, c.coefOf(2))
        assertEquals(1L, c.positiveDegree())
    }

    @Test
    fun `resolution scales unequal pivot coefficients to cancel`() {
        // C: 3·x0 + 2·x1 ≥ 3 ; R: 2·¬x0 + 5·x2 ≥ 4. Cancel x0 with mulSelf=2, mulOther=3.
        val c = acc { loadPb(longArrayOf(3, 2), intArrayOf(Lit.make(0, true), Lit.make(1, true)), geBound = 3) }
        val r = acc { loadPb(longArrayOf(2, 5), intArrayOf(Lit.make(0, false), Lit.make(2, true)), geBound = 4) }
        // C has coef(x0)=+3 ; R has coef(x0)=-2. 2·(+3) + 3·(-2) = 0.
        assertTrue(c.addScaled(r, mulSelf = 2, mulOther = 3))
        assertEquals(0L, c.coefOf(0), "pivot cancels")
        assertEquals(4L, c.coefOf(1)) // 2·2
        assertEquals(15L, c.coefOf(2)) // 3·5
    }

    @Test
    fun `saturation caps coefficients at the degree without changing the 0-1 solution set`() {
        // 5·x0 ≥ 3  ⇔  x0=1. After saturation: 3·x0 ≥ 3  ⇔  x0=1.
        val a = acc { loadPb(longArrayOf(5), intArrayOf(Lit.make(0, true)), geBound = 3) }
        a.saturate()
        assertEquals(3L, a.coefOf(0))
        assertEquals(3L, a.positiveDegree())
    }

    @Test
    fun `divide-and-round-up is a valid cutting plane`() {
        // 3·x0 + 3·x1 ≥ 4  ⇔  x0+x1 ≥ 2. Divide by 3, round up: x0 + x1 ≥ 2.
        val a = acc { loadPb(longArrayOf(3, 3), intArrayOf(Lit.make(0, true), Lit.make(1, true)), geBound = 4) }
        a.divideRoundUp(3)
        assertEquals(1L, a.coefOf(0))
        assertEquals(1L, a.coefOf(1))
        assertEquals(2L, a.positiveDegree())
    }

    @Test
    fun `gcd normalization divides through and rounds the degree up`() {
        // 2·x0 + 4·x1 ≥ 4  ⇔  x0 + 2·x1 ≥ 2.
        val a = acc { loadPb(longArrayOf(2, 4), intArrayOf(Lit.make(0, true), Lit.make(1, true)), geBound = 4) }
        a.normalizeByGcd()
        assertEquals(1L, a.coefOf(0))
        assertEquals(2L, a.coefOf(1))
        assertEquals(2L, a.positiveDegree())
    }

    @Test
    fun `materialize emits a positive-literal constraint and drops trivial ones`() {
        val a = acc { loadClause(intArrayOf(Lit.make(0, true), Lit.make(1, false))) }
        val m = assertNotNull(a.materialize())
        assertEquals(1L, m.degree)
        assertEquals(2, m.literals.size)
        // A trivially-true constraint (degree ≤ 0) materializes to null.
        val trivial = acc { rhs = 0L }
        assertNull(trivial.materialize())
    }

    @Test
    fun `addScaled reports overflow instead of wrapping`() {
        val a = acc { loadPb(longArrayOf(Long.MAX_VALUE / 2), intArrayOf(Lit.make(0, true)), geBound = 1) }
        val b = acc { loadPb(longArrayOf(Long.MAX_VALUE / 2), intArrayOf(Lit.make(0, false)), geBound = 1) }
        assertFalse(a.addScaled(b, mulSelf = 4, mulOther = 1), "coefficient overflow must be reported")
    }
}
