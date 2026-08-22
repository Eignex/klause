package com.eignex.klause.simplex.exact

/**
 * Fixed-width exact rational: a signed 128-bit numerator over a positive 128-bit denominator, always
 * gcd-normalized. The first level of the exact rational arithmetic ([FracOps]): pivot chains whose
 * values stay within 128 bits — the overwhelmingly common case at a leaf — run here with two-word
 * integer operations (Stein gcd, shift-subtract division) and no big-integer allocation; the first
 * operation that would escape 128 bits latches the level's overflow flag and the caller escalates to
 * [BigFraction].
 *
 * Numerator sign is carried in [nHi]'s top bit (two's complement over the (hi, lo) pair); the
 * denominator is always strictly positive.
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

/** Mutable unsigned 128-bit scratch for the [Frac128] integer kernels. */
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

/** `a -= b`; requires `a >= b`. */
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

/** Binary (Stein) gcd of two magnitudes; either may be zero. Result overwrites [a]. */
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

/** Shift-subtract `a / b` (b non-zero): quotient into [q], remainder into [a]. */
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

/** Full 256-bit product of two 128-bit magnitudes into four 64-bit limbs (little-endian).
 *  Classic schoolbook with a running carry: each 64×64 partial product `(pHi, pLo)` adds its low
 *  word (plus the carry so far) at position `i + j`, and the next carry is `pHi` plus the add's
 *  carry-outs — which cannot wrap, since a 64×64 product's high word is at most 2⁶⁴ − 2³³. */
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

/** `a * b` into [out] when the product fits 128 bits; false (out unspecified) otherwise. */
@OptIn(ExperimentalUnsignedTypes::class)
private fun mulU(a: U128, b: U128, out: U128): Boolean {
    val limbs = ULongArray(4)
    mul256(a, b, limbs)
    if (limbs[2] != 0uL || limbs[3] != 0uL) return false
    out.lo = limbs[0]
    out.hi = limbs[1]
    return true
}

/** Signed magnitude of the numerator pair. */
private fun magOf(hi: Long, lo: Long): U128 {
    if (hi >= 0L) return U128(hi.toULong(), lo.toULong())
    val negLo = 0uL - lo.toULong()
    val borrow = if (lo != 0L) 1uL else 0uL
    val negHi = hi.toULong().inv() + (1uL - borrow)
    return U128(negHi, negLo)
}

/** The signed (hi, lo) pair for a magnitude with the given sign, or null when it exceeds 2^127−1
 *  (or 2^127 for the negative side). */
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

/**
 * The 128-bit [FracOps] level. Any operation whose exact result escapes 128 bits latches
 * [overflowed] and returns [Frac128.ZERO]; the caller must treat a latched run as void and escalate.
 */
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
        if (!mulU(na, db, p1) || !mulU(nb, da, p2) || !mulU(da, db, d)) return latch()
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

    override fun times(a: Frac128, b: Frac128): Frac128 {
        if (latched) return Frac128.ZERO
        if (a.isZero || b.isZero) return zero
        val na = magOf(a.nHi, a.nLo)
        val nb = magOf(b.nHi, b.nLo)
        val da = U128(a.dHi.toULong(), a.dLo.toULong())
        val db = U128(b.dHi.toULong(), b.dLo.toULong())
        val n = U128(0uL, 0uL)
        val d = U128(0uL, 0uL)
        if (!mulU(na, nb, n) || !mulU(da, db, d)) return latch()
        return normalized((a.nHi < 0L) xor (b.nHi < 0L), n, d) ?: latch()
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

    /** Reduce `±mag / den` by the gcd and re-sign; null when the signed numerator cannot fit. */
    private fun normalized(negative: Boolean, mag: U128, den: U128): Frac128? {
        if (mag.isZero()) return zero
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
