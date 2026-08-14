package com.eignex.klause.lp

import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Column Hermite normal form. The two properties that matter downstream are checked on every case rather
 * than the exact entries: the factorisation `H = A·V` holds, and `V` is unimodular — without the second,
 * `x = Vy` is not a bijection of the integer lattice and the transformed system is not equisatisfiable.
 */
class HermiteFormTest {

    private fun mat(vararg rows: LongArray): Array<Array<BigInteger>> =
        Array(rows.size) { i -> Array(rows[i].size) { j -> BigInteger.fromLong(rows[i][j]) } }

    private fun multiply(a: Array<Array<BigInteger>>, b: Array<Array<BigInteger>>): Array<Array<BigInteger>> {
        val n = if (b.isEmpty()) 0 else b[0].size
        return Array(a.size) { i ->
            Array(n) { j ->
                var acc = BigInteger.ZERO
                for (k in b.indices) acc += a[i][k] * b[k][j]
                acc
            }
        }
    }

    /** `det` by fraction-free Gaussian elimination — only used to assert unimodularity (`±1`). */
    private fun determinant(m: Array<Array<BigInteger>>): BigInteger {
        val n = m.size
        if (n == 0) return BigInteger.ONE
        val a = Array(n) { i -> Array(n) { j -> m[i][j] } }
        var sign = 1
        var prev = BigInteger.ONE
        for (k in 0 until n - 1) {
            if (a[k][k].isZero()) {
                val swap = (k + 1 until n).firstOrNull { !a[it][k].isZero() } ?: return BigInteger.ZERO
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

    private fun assertWellFormed(a: Array<Array<BigInteger>>, f: HermiteForm) {
        assertEquals(
            multiply(a, f.v).map { it.toList() },
            f.h.map { it.toList() },
            "H must equal A·V",
        )
        val det = determinant(f.v)
        assertTrue(det == BigInteger.ONE || det == -BigInteger.ONE, "V must be unimodular, det was $det")
    }

    @Test
    fun `a two by two matrix factorises with a unimodular transform`() {
        val a = mat(longArrayOf(2, 3), longArrayOf(4, 5))
        assertWellFormed(a, hermiteNormalForm(a))
    }

    @Test
    fun `each row is zero to the right of its pivot`() {
        val a = mat(longArrayOf(6, 4, 10), longArrayOf(3, 9, 6))
        val f = hermiteNormalForm(a)
        assertWellFormed(a, f)
        // Row 0 pivots in column 0, so everything right of it is zero — the lower-triangular shape the
        // reduction relies on to derive a bound for one variable per pivot row.
        for (j in 1 until 3) assertEquals(BigInteger.ZERO, f.h[0][j], "row 0 column $j must be cleared")
    }

    @Test
    fun `the pivot entry is made positive`() {
        val a = mat(longArrayOf(-4, 6))
        val f = hermiteNormalForm(a)
        assertWellFormed(a, f)
        assertTrue(f.h[0][0] > BigInteger.ZERO, "pivot must be positive, was ${f.h[0][0]}")
    }

    @Test
    fun `a gcd row reduces to the gcd`() {
        // The first row's entries have gcd 2, which the column Euclid step must expose as the pivot.
        val a = mat(longArrayOf(6, 10, 14))
        val f = hermiteNormalForm(a)
        assertWellFormed(a, f)
        assertEquals(BigInteger.fromLong(2), f.h[0][0])
    }

    @Test
    fun `an identity input is already in form`() {
        val a = mat(longArrayOf(1, 0), longArrayOf(0, 1))
        val f = hermiteNormalForm(a)
        assertWellFormed(a, f)
        assertEquals(BigInteger.ONE, f.h[0][0])
        assertEquals(BigInteger.ONE, f.h[1][1])
    }

    @Test
    fun `a zero row leaves the remaining columns to later pivots`() {
        val a = mat(longArrayOf(0, 0), longArrayOf(2, 3))
        val f = hermiteNormalForm(a)
        assertWellFormed(a, f)
        assertEquals(BigInteger.ONE, f.h[1][0], "row 1 pivots at the gcd of 2 and 3")
    }

    @Test
    fun `intermediate coefficients stay bounded on an adversarial matrix`() {
        // The column-Euclidean form is the version cited for intermediate coefficient explosion, so the
        // growth is measured rather than assumed: a dense matrix of coprime-ish entries is its bad case.
        val n = 8
        val primes = longArrayOf(1000003, 999983, 1000033, 999979, 1000037, 999961, 1000039, 999959)
        val m = Array(n) { i -> Array(n) { j -> BigInteger.fromLong(primes[(i * 3 + j * 5) % n] + i * 7L + j) } }
        val f = hermiteNormalForm(m)
        var widest = 0
        for (row in f.h) for (e in row) widest = maxOf(widest, e.abs().toString().length)
        for (row in f.v) for (e in row) widest = maxOf(widest, e.abs().toString().length)
        // Measured: 18 digits out of 7-digit inputs. The pivot-smallest rule plus the canonical
        // reduction hold growth to roughly 2.5x here, so this form is not the blow-up its reputation
        // suggests on this shape. The ceiling is generous but fails loudly if it is ever swapped for one
        // that does explode.
        assertTrue(widest < 40, "widest entry was $widest digits")
    }
}
