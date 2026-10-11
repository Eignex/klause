package com.eignex.klause.lp.lattice

import com.eignex.klause.lp.lattice.bareissEchelon
import com.eignex.klause.lp.lattice.mixedEchelonHermite
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The right-hand side must take every row operation the coefficients take. A reduced row paired with the
 * bound of whichever input row a swap moved into its slot states a constraint the model never had, and a
 * bound derived from it closes a domain without the clamp flag — so the error surfaces as a false `unsat`.
 */
class BareissRhsTest {

    private fun vec(vararg v: Long) = Array(v.size) { bigIntOf(v[it]) }

    @Test
    fun `a row swap carries its right-hand side along`() {
        // Column 0 is zero in the first row, so elimination swaps the rows; the bounds must swap too.
        val e = bareissEchelon(sparseRows(longArrayOf(0, 1), longArrayOf(1, 0)), 2, vec(3, 5))
        assertEquals(bigIntOf(5), e.rhs[0], "row now holding `x = 5` must carry 5, not 3")
        assertEquals(bigIntOf(3), e.rhs[1])
    }

    @Test
    fun `a dependent row with a mismatched bound is reported inconsistent`() {
        // x + y = 4 and 2x + 2y = 9 reduce to 0 = 1: the equalities alone have no solution.
        val e = bareissEchelon(sparseRows(longArrayOf(1, 1), longArrayOf(2, 2)), 2, vec(4, 9))
        assertTrue(e.inconsistent, "0 = 1 refutes the system")
    }

    @Test
    fun `a row the others imply is reported by its input index`() {
        // x + y = 3, y + z = 4, x + 2y + z = 7: the third is the sum of the first two.
        val e = bareissEchelon(
            sparseRows(longArrayOf(1, 1, 0), longArrayOf(0, 1, 1), longArrayOf(1, 2, 1)),
            3,
            vec(3, 4, 7),
        )
        assertEquals(listOf(2), e.dependentRows.toList())
    }

    @Test
    fun `a row reduced to a nonzero constant is not reported dependent`() {
        val e = bareissEchelon(sparseRows(longArrayOf(1, 1), longArrayOf(2, 2)), 2, vec(4, 9))
        assertEquals(emptyList(), e.dependentRows.toList(), "0 = 1 refutes the system rather than repeating it")
    }

    @Test
    fun `the transformation exposes the reduced right-hand sides`() {
        val m = mixedEchelonHermite(sparseRows(longArrayOf(0, 1), longArrayOf(1, 0)), emptyList(), 2, vec(3, 5))
        assertEquals(m.equalities.size, m.equalityRhs.size, "one bound per reduced row")
    }
}
