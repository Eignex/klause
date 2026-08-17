package com.eignex.klause.lp

import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The right-hand side must take every row operation the coefficients take. A reduced row paired with the
 * bound of whichever input row a swap moved into its slot states a constraint the model never had, and a
 * bound derived from it closes a domain without the clamp flag — so the error surfaces as a false `unsat`.
 */
class BareissRhsTest {

    private fun vec(vararg v: Long) = Array(v.size) { BigInteger.fromLong(v[it]) }

    @Test
    fun `a row swap carries its right-hand side along`() {
        // Column 0 is zero in the first row, so elimination swaps the rows; the bounds must swap too.
        val e = bareissEchelon(sparseRows(longArrayOf(0, 1), longArrayOf(1, 0)), 2, vec(3, 5))
        assertEquals(BigInteger.fromLong(5), e.rhs[0], "row now holding `x = 5` must carry 5, not 3")
        assertEquals(BigInteger.fromLong(3), e.rhs[1])
    }

    @Test
    fun `an elimination combines right-hand sides the same way it combines rows`() {
        // x + y = 4 and 2x + 2y = 8 are dependent; the second reduces to 0 = 0.
        val e = bareissEchelon(sparseRows(longArrayOf(1, 1), longArrayOf(2, 2)), 2, vec(4, 8))
        assertEquals(1, e.rows.size, "the dependent row drops out")
        assertEquals(BigInteger.fromLong(4), e.rhs[0])
        assertFalse(e.inconsistent, "8 = 2*4, so the pair is consistent")
    }

    @Test
    fun `a dependent row with a mismatched bound is reported inconsistent`() {
        // x + y = 4 and 2x + 2y = 9 reduce to 0 = 1: the equalities alone have no solution.
        val e = bareissEchelon(sparseRows(longArrayOf(1, 1), longArrayOf(2, 2)), 2, vec(4, 9))
        assertTrue(e.inconsistent, "0 = 1 refutes the system")
    }

    @Test
    fun `coefficients alone still reduce with no right-hand side claimed`() {
        val e = bareissEchelon(sparseRows(longArrayOf(0, 1), longArrayOf(1, 0)), 2)
        assertEquals(2, e.rows.size)
        assertEquals(0, e.rhs.size, "nothing is claimed about bounds that were never supplied")
        assertFalse(e.inconsistent, "inconsistency cannot be seen without the bounds")
    }

    @Test
    fun `the transformation exposes the reduced right-hand sides`() {
        val m = mixedEchelonHermite(sparseRows(longArrayOf(0, 1), longArrayOf(1, 0)), emptyList(), 2, vec(3, 5))
        assertEquals(m.equalities.size, m.equalityRhs.size, "one bound per reduced row")
    }
}
