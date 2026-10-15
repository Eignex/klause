package com.eignex.klause.lp.lattice

import com.eignex.klause.lp.lattice.HermiteForm
import com.eignex.klause.lp.lattice.SparseIntRow
import com.eignex.klause.lp.lattice.hermiteNormalForm
import com.eignex.klause.lp.lattice.sparseIntRow
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.isZero
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HermiteFormTest {

    private fun form(vararg rows: LongArray): HermiteForm {
        val f = hermiteNormalForm(sparseRows(*rows), rows[0].size)
        assertNotNull(f)
        assertWellFormed(sparseRows(*rows), rows[0].size, f)
        return f
    }

    private fun assertWellFormed(a: List<SparseIntRow>, cols: Int, f: HermiteForm) {
        for (i in a.indices) {
            for (j in 0 until cols) {
                var acc = BIG_ZERO
                for (k in 0 until cols) acc += a[i][k] * f.v[k, j]
                assertEquals(acc, f.h[i][j], "H($i, $j) must equal (A*V)($i, $j)")
            }
        }
        val det = determinant(Array(cols) { i -> Array(cols) { j -> f.v[i, j] } })
        assertTrue(det == BIG_ONE || det == -BIG_ONE, "V must be unimodular, det was $det")
    }

    private fun determinant(m: Array<Array<BigInt>>): BigInt {
        val n = m.size
        if (n == 0) return BIG_ONE
        val a = Array(n) { i -> Array(n) { j -> m[i][j] } }
        var sign = 1
        var prev = BIG_ONE
        for (k in 0 until n - 1) {
            if (a[k][k].isZero()) {
                val swap = (k + 1 until n).firstOrNull { !a[it][k].isZero() } ?: return BIG_ZERO
                val t = a[k]
                a[k] = a[swap]
                a[swap] = t
                sign = -sign
            }
            for (i in k + 1 until n) {
                for (j in k + 1 until n) {
                    a[i][j] = (a[i][j] * a[k][k] - a[i][k] * a[k][j]) / prev
                }
            }
            prev = a[k][k]
        }
        val d = a[n - 1][n - 1]
        return if (sign < 0) -d else d
    }

    @Test
    fun `two by two matrix should factorise with a unimodular transform`() {
        form(longArrayOf(2, 3), longArrayOf(4, 5))
    }

    @Test
    fun `hermite form should make the pivot entry positive`() {
        val f = form(longArrayOf(-4, 6))
        assertTrue(f.h[0][0] > BIG_ZERO, "pivot must be positive, was ${f.h[0][0]}")
    }

    @Test
    fun `gcd row should reduce to the gcd`() {
        // The first row's entries have gcd 2, which the column Euclid step must expose as the pivot.
        val f = form(longArrayOf(6, 10, 14))
        assertEquals(bigIntOf(2), f.h[0][0])
    }

    @Test
    fun `zero row should leave the remaining columns to later pivots`() {
        val f = form(longArrayOf(0, 0), longArrayOf(2, 3))
        assertEquals(BIG_ONE, f.h[1][0])
    }

    @Test
    fun `hermite form should keep coefficients bounded on an adversarial matrix`() {
        val n = 8
        val primes = longArrayOf(1000003, 999983, 1000033, 999979, 1000037, 999961, 1000039, 999959)
        val m = List(n) { i ->
            sparseIntRow(
                (0 until n).associateWith { j -> bigIntOf(primes[(i * 3 + j * 5) % n] + i * 7L + j) },
            )
        }
        val f = hermiteNormalForm(m, n)
        assertNotNull(f)
        var widest = 0
        for (row in f.h) for (e in row.value) widest = maxOf(widest, e.abs().toString().length)
        for (i in 0 until n) for (j in 0 until n) widest = maxOf(widest, f.v[i, j].abs().toString().length)
        // 7-digit inputs reach 18 digits; the ceiling leaves room but catches an exploding form.
        assertTrue(widest < 40, "widest entry was $widest digits")
    }
}
