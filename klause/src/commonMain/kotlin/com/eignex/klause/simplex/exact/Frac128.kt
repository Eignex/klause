package com.eignex.klause.simplex.exact

import com.eignex.util.numbers.BigFraction
/**
 * Gcd-normalized signed 128-bit numerator (two's complement over `(nHi, nLo)`) over a positive 128-bit
 * denominator. Pivot chains that stay within 128 bits run here without big-integer allocation; the first
 * operation that escapes latches the overflow flag and the caller escalates to [BigFraction].
 */
internal class Frac128(val nHi: Long, val nLo: Long, val dHi: Long, val dLo: Long) {
    val isZero: Boolean get() = nHi == 0L && nLo == 0L

    override fun toString(): String = "Frac128(${toDoubleApprox()})"

    internal fun toDoubleApprox(): Double {
        // Convert the magnitude, then apply the sign: summing the signed two's-complement words in
        // doubles cancels catastrophically for small negative values (ULP at 2^64 is 2048).
        val negative = nHi < 0L
        val magLo: ULong
        val magHi: ULong
        if (negative) {
            magLo = 0uL - nLo.toULong()
            val borrow = if (nLo != 0L) 1uL else 0uL
            magHi = nHi.toULong().inv() + (1uL - borrow)
        } else {
            magLo = nLo.toULong()
            magHi = nHi.toULong()
        }
        val n = magHi.toDouble() * TWO_POW_64 + magLo.toDouble()
        val d = dHi.toULong().toDouble() * TWO_POW_64 + (dLo.toULong()).toDouble()
        return (if (negative) -n else n) / d
    }

    companion object {
        val ZERO = Frac128(0L, 0L, 0L, 1L)
        val ONE = Frac128(0L, 1L, 0L, 1L)
        val MINUS_ONE = Frac128(-1L, -1L, 0L, 1L)
        private const val TWO_POW_64 = 1.8446744073709552E19
    }
}

private class U128(var hi: ULong, var lo: ULong) {
    fun isZero(): Boolean = hi == 0uL && lo == 0uL

    fun set(other: U128) {
        hi = other.hi
        lo = other.lo
    }

    fun copy(): U128 = U128(hi, lo)
}

private fun cmpU(a: U128, b: U128): Int = when {
    a.hi != b.hi -> a.hi.compareTo(b.hi)
    else -> a.lo.compareTo(b.lo)
}

// `a -= b`; requires `a >= b`.
private fun subU(a: U128, b: U128) {
    val newLo = a.lo - b.lo
    val borrow = if (a.lo < b.lo) 1uL else 0uL
    a.hi = a.hi - b.hi - borrow
    a.lo = newLo
}

private fun shl1U(a: U128) {
    a.hi = (a.hi shl 1) or (a.lo shr 63)
    a.lo = a.lo shl 1
}

private fun shr1U(a: U128) {
    a.lo = (a.lo shr 1) or (a.hi shl 63)
    a.hi = a.hi shr 1
}

private fun shrU(a: U128, k: Int) {
    var left = k
    while (left >= 64) {
        a.lo = a.hi
        a.hi = 0uL
        left -= 64
    }
    if (left > 0) {
        a.lo = (a.lo shr left) or (a.hi shl (64 - left))
        a.hi = a.hi shr left
    }
}

private fun ctzU(a: U128): Int = if (a.lo != 0uL) {
    a.lo.countTrailingZeroBits()
} else {
    64 + a.hi.countTrailingZeroBits()
}

private fun bitLenU(a: U128): Int = if (a.hi != 0uL) {
    128 - a.hi.countLeadingZeroBits()
} else {
    64 - a.lo.countLeadingZeroBits()
}

// Binary gcd into `a`; either may be zero. J. Stein, "Computational problems associated with Racah algebra",
// J. Comput. Phys. 1(3), 1967, https://doi.org/10.1016/0021-9991(67)90047-2.
private fun gcdU(a: U128, b: U128) {
    if (a.isZero()) {
        a.set(b)
        return
    }
    if (b.isZero()) return
    val x = a.copy()
    val y = b.copy()
    val shift = minOf(ctzU(x), ctzU(y))
    shrU(x, ctzU(x))
    while (true) {
        shrU(y, ctzU(y))
        if (cmpU(x, y) > 0) {
            val t = x.copy()
            x.set(y)
            y.set(t)
        }
        subU(y, x)
        if (y.isZero()) break
    }
    a.set(x)
    var left = shift
    while (left-- > 0) shl1U(a)
}

// Quotient into `q`, remainder into `a`; `b` must be non-zero.
private fun divModU(a: U128, b: U128, q: U128) {
    q.hi = 0uL
    q.lo = 0uL
    if (cmpU(a, b) < 0) return
    var shift = bitLenU(a) - bitLenU(b)
    val d = b.copy()
    var left = shift
    while (left-- > 0) shl1U(d)
    while (shift >= 0) {
        shl1U(q)
        if (cmpU(a, d) >= 0) {
            subU(a, d)
            q.lo = q.lo or 1uL
        }
        shr1U(d)
        shift--
    }
}

// Little-endian limbs. The running carry cannot wrap, since
// `p + out(i + j) + carry ≤ (2⁶⁴ − 1)² + 2(2⁶⁴ − 1) = 2¹²⁸ − 1`.
@OptIn(ExperimentalUnsignedTypes::class)
private fun mul256(a: U128, b: U128, out: ULongArray) {
    out.fill(0uL)
    val aw = ulongArrayOf(a.lo, a.hi)
    val bw = ulongArrayOf(b.lo, b.hi)
    for (i in 0 until 2) {
        var carry = 0uL
        for (j in 0 until 2) {
            val aLo = aw[i] and 0xFFFFFFFFuL
            val aHi = aw[i] shr 32
            val bLo = bw[j] and 0xFFFFFFFFuL
            val bHi = bw[j] shr 32
            val ll = aLo * bLo
            val lh = aLo * bHi
            val hl = aHi * bLo
            val hh = aHi * bHi
            val cross = (ll shr 32) + (lh and 0xFFFFFFFFuL) + (hl and 0xFFFFFFFFuL)
            val pLo = (cross shl 32) or (ll and 0xFFFFFFFFuL)
            val pHi = hh + (lh shr 32) + (hl shr 32) + (cross shr 32)
            val s1 = out[i + j] + pLo
            var c = if (s1 < pLo) 1uL else 0uL
            val s2 = s1 + carry
            if (s2 < s1) c += 1uL
            out[i + j] = s2
            carry = pHi + c
        }
        // Deposit the final carry with propagation (the i = 0 pass may have left out[i + 2] nonzero).
        var k = i + 2
        var add = carry
        while (add != 0uL && k < 4) {
            val s = out[k] + add
            add = if (s < add) 1uL else 0uL
            out[k] = s
            k++
        }
    }
}

// False, with `out` unspecified, when the product exceeds 128 bits.
@OptIn(ExperimentalUnsignedTypes::class)
private fun mulU(a: U128, b: U128, out: U128): Boolean {
    val limbs = ULongArray(4)
    mul256(a, b, limbs)
    if (limbs[2] != 0uL || limbs[3] != 0uL) return false
    out.lo = limbs[0]
    out.hi = limbs[1]
    return true
}

private fun magOf(hi: Long, lo: Long): U128 {
    if (hi >= 0L) return U128(hi.toULong(), lo.toULong())
    val negLo = 0uL - lo.toULong()
    val borrow = if (lo != 0L) 1uL else 0uL
    val negHi = hi.toULong().inv() + (1uL - borrow)
    return U128(negHi, negLo)
}

// Null when the magnitude exceeds 2^127−1 (2^127 on the negative side).
private fun signedOf(negative: Boolean, mag: U128): Frac128Num? {
    if (mag.hi shr 63 != 0uL) {
        // Magnitude uses bit 127: only −2^127 is representable.
        return if (negative && mag.hi == (1uL shl 63) && mag.lo == 0uL) {
            Frac128Num(Long.MIN_VALUE, 0L)
        } else {
            null
        }
    }
    if (!negative) return Frac128Num(mag.hi.toLong(), mag.lo.toLong())
    val negLo = 0uL - mag.lo
    val borrow = if (mag.lo != 0uL) 1uL else 0uL
    val negHi = mag.hi.inv() + (1uL - borrow)
    return Frac128Num(negHi.toLong(), negLo.toLong())
}

private class Frac128Num(val hi: Long, val lo: Long)

// An escaping result latches overflowed() and returns zero; the caller treats a latched run as void.
internal class Frac128Ops : FracOps<Frac128> {
    private var latched = false

    override val zero: Frac128 = Frac128.ZERO
    override val one: Frac128 = Frac128.ONE
    override val minusOne: Frac128 = Frac128.MINUS_ONE
    override val half: Frac128 = Frac128(0L, 1L, 0L, 2L)

    override fun overflowed(): Boolean = latched

    private fun latch(): Frac128 {
        latched = true
        return Frac128.ZERO
    }

    override fun ofLong(v: Long): Frac128 = Frac128(if (v < 0L) -1L else 0L, v, 0L, 1L)

    override fun ofDouble(v: Double): Frac128? {
        if (v == 0.0) return zero
        if (!v.isFinite()) return null
        val bits = v.toRawBits()
        val expBits = ((bits ushr 52) and 0x7FFL).toInt()
        var m = bits and 0xFFFFFFFFFFFFFL
        var e = if (expBits == 0) {
            -1074
        } else {
            m = m or (1L shl 52)
            expBits - 1075
        }
        val tz = m.countTrailingZeroBits()
        m = m shr tz
        e += tz
        val neg = bits < 0L
        return when {
            e >= 0 -> {
                if (e > 126 - 53) return null
                val mag = U128(0uL, m.toULong())
                var left = e
                while (left-- > 0) shl1U(mag)
                val num = signedOf(neg, mag) ?: return null
                Frac128(num.hi, num.lo, 0L, 1L)
            }

            else -> {
                if (-e > 126) return null
                val den = U128(0uL, 1uL)
                var left = -e
                while (left-- > 0) shl1U(den)
                Frac128(if (neg) -1L else 0L, if (neg) -m else m, den.hi.toLong(), den.lo.toLong())
            }
        }
    }

    override fun signum(a: Frac128): Int = when {
        a.nHi < 0L -> -1
        a.nHi == 0L && a.nLo == 0L -> 0
        else -> 1
    }

    override fun toDouble(a: Frac128): Double = a.toDoubleApprox()

    override fun plus(a: Frac128, b: Frac128): Frac128 = addSub(a, b, negateB = false)

    override fun minus(a: Frac128, b: Frac128): Frac128 = addSub(a, b, negateB = true)

    private fun addSub(a: Frac128, b: Frac128, negateB: Boolean): Frac128 {
        if (latched) return Frac128.ZERO
        if (b.isZero) return a
        if (a.isZero) return if (negateB) negated(b) else b
        if (a.dHi == 0L && a.dLo == 1L && b.dHi == 0L && b.dLo == 1L) {
            return combineIntegers(a, b, subtract = negateB)
        }
        // n = na·db ± nb·da over d = da·db, then normalize.
        val da = U128(a.dHi.toULong(), a.dLo.toULong())
        val db = U128(b.dHi.toULong(), b.dLo.toULong())
        val na = magOf(a.nHi, a.nLo)
        val nb = magOf(b.nHi, b.nLo)
        val negA = a.nHi < 0L
        val negB = (b.nHi < 0L) xor negateB
        val p1 = U128(0uL, 0uL)
        val p2 = U128(0uL, 0uL)
        val d = U128(0uL, 0uL)
        if (a.dHi == b.dHi && a.dLo == b.dLo) {
            p1.set(na)
            p2.set(nb)
            d.set(da)
        } else if (!mulU(na, db, p1) || !mulU(nb, da, p2) || !mulU(da, db, d)) return latch()
        val negOut: Boolean
        val mag: U128
        if (negA == negB) {
            val newLo = p1.lo + p2.lo
            val carryLo = if (newLo < p1.lo) 1uL else 0uL
            val hiSum = p1.hi + p2.hi
            val ov1 = hiSum < p1.hi
            val newHi = hiSum + carryLo
            val ov2 = newHi < hiSum
            if (ov1 || ov2) return latch()
            negOut = negA
            mag = U128(newHi, newLo)
        } else if (cmpU(p1, p2) >= 0) {
            subU(p1, p2)
            negOut = negA
            mag = p1
        } else {
            subU(p2, p1)
            negOut = negB
            mag = p2
        }
        return normalized(negOut, mag, d) ?: latch()
    }

    private fun combineIntegers(a: Frac128, b: Frac128, subtract: Boolean): Frac128 {
        val low: Long
        val high: Long
        if (subtract) {
            low = a.nLo - b.nLo
            val borrow = if (a.nLo.toULong() < b.nLo.toULong()) 1L else 0L
            high = a.nHi - b.nHi - borrow
        } else {
            low = a.nLo + b.nLo
            val carry = if (low.toULong() < a.nLo.toULong()) 1L else 0L
            high = a.nHi + b.nHi + carry
        }
        val oppositeSigns = (a.nHi xor b.nHi) < 0L
        if (oppositeSigns == subtract && (a.nHi xor high) < 0L) return latch()
        return if (high == 0L && low == 0L) zero else Frac128(high, low, 0L, 1L)
    }

    override fun times(a: Frac128, b: Frac128): Frac128 {
        if (latched) return Frac128.ZERO
        if (a.isZero || b.isZero) return zero
        if (a.dHi == 0L && a.dLo == 1L) {
            if (a.nHi == 0L && a.nLo == 1L) return b
            if (a.nHi == -1L && a.nLo == -1L) return negated(b)
        }
        if (b.dHi == 0L && b.dLo == 1L) {
            if (b.nHi == 0L && b.nLo == 1L) return a
            if (b.nHi == -1L && b.nLo == -1L) return negated(a)
        }
        val na = magOf(a.nHi, a.nLo)
        val nb = magOf(b.nHi, b.nLo)
        val da = U128(a.dHi.toULong(), a.dLo.toULong())
        val db = U128(b.dHi.toULong(), b.dLo.toULong())
        val n = U128(0uL, 0uL)
        val d = U128(0uL, 0uL)
        if (!mulU(na, nb, n) || !mulU(da, db, d)) return latch()
        return normalized((a.nHi < 0L) xor (b.nHi < 0L), n, d) ?: latch()
    }

    private fun negated(value: Frac128): Frac128 {
        if (value.nHi == Long.MIN_VALUE && value.nLo == 0L) return latch()
        return Frac128(value.nHi.inv() + if (value.nLo == 0L) 1L else 0L, -value.nLo,
            value.dHi, value.dLo)
    }

    override fun reciprocal(a: Frac128): Frac128 {
        if (latched) return Frac128.ZERO
        require(!a.isZero) { "reciprocal of zero" }
        val n = magOf(a.nHi, a.nLo)
        val d = U128(a.dHi.toULong(), a.dLo.toULong())
        return normalized(a.nHi < 0L, d, n) ?: latch()
    }

    override fun compare(a: Frac128, b: Frac128): Int {
        val diff = minus(a, b)
        return if (latched) 0 else signum(diff)
    }

    // Null when the signed numerator cannot fit.
    private fun normalized(negative: Boolean, mag: U128, den: U128): Frac128? {
        if (mag.isZero()) return zero
        if (den.hi == 0uL && den.lo == 1uL) {
            val num = signedOf(negative, mag) ?: return null
            return Frac128(num.hi, num.lo, 0L, 1L)
        }
        val g = mag.copy()
        gcdU(g, den)
        val one = U128(0uL, 1uL)
        val n = mag.copy()
        val d = den.copy()
        if (cmpU(g, one) > 0) {
            val q = U128(0uL, 0uL)
            divModU(n, g, q)
            n.set(q)
            val q2 = U128(0uL, 0uL)
            divModU(d, g, q2)
            d.set(q2)
        }
        // Denominator must stay in the positive signed range.
        if (d.hi shr 63 != 0uL) return null
        val num = signedOf(negative, n) ?: return null
        return Frac128(num.hi, num.lo, d.hi.toLong(), d.lo.toLong())
    }
}
