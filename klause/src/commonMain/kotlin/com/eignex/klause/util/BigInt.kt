package com.eignex.klause.util

/**
 * Minimal arbitrary-precision signed integer for Kotlin Multiplatform (no `java.math.BigInteger`
 * dependency, which is absent on native/wasm). Sign-magnitude: [sign] is `-1`, `0`, or `1`, and
 * [mag] is the magnitude as little-endian base-2³² limbs, normalized so the top limb is non-zero
 * (zero is `sign = 0`, empty [mag]).
 *
 * Built for klause's exact LP basis-certification: the determinant and adjugate of a node's basis
 * can outgrow `Long` (the dense-tableau Bareiss path hits this as `LpOverflowException`), so the
 * certify step does its one rational solve in [BigInt] instead. Scope is deliberately small —
 * add / subtract / multiply / divide-remainder / gcd / compare — enough for fraction-free
 * elimination and rounding the resulting bound. Division is Knuth Algorithm D (schoolbook base-2³²);
 * it is the fallback path for the rare large-determinant basis, but that is exactly when the numbers
 * are big, so it is kept performant rather than bit-at-a-time. Validated against `java.math.BigInteger`.
 */
internal class BigInt private constructor(val sign: Int, val mag: IntArray) : Comparable<BigInt> {

    val isZero: Boolean get() = sign == 0

    fun signum(): Int = sign

    operator fun unaryMinus(): BigInt = if (sign == 0) this else BigInt(-sign, mag)

    fun abs(): BigInt = if (sign >= 0) this else BigInt(1, mag)

    operator fun plus(other: BigInt): BigInt {
        if (sign == 0) return other
        if (other.sign == 0) return this
        if (sign == other.sign) return create(sign, addMag(mag, other.mag))
        val c = cmpMag(mag, other.mag)
        return when {
            c == 0 -> ZERO
            c > 0 -> create(sign, subMag(mag, other.mag))
            else -> create(other.sign, subMag(other.mag, mag))
        }
    }

    operator fun minus(other: BigInt): BigInt = this + (-other)

    operator fun times(other: BigInt): BigInt {
        if (sign == 0 || other.sign == 0) return ZERO
        return create(sign * other.sign, mulMag(mag, other.mag))
    }

    /** Quotient and remainder of truncated division (`q` rounds toward zero, `r` has the dividend's
     *  sign), so `this == q * other + r` and `|r| < |other|`. Throws on division by zero. */
    fun divideAndRemainder(other: BigInt): Pair<BigInt, BigInt> {
        check(other.sign != 0) { "BigInt division by zero" }
        val c = cmpMag(mag, other.mag)
        if (c < 0) return ZERO to this // |this| < |other|
        if (c == 0) return BigInt(sign * other.sign, ONE_MAG) to ZERO
        val (q, r) = divmodMag(mag, other.mag)
        return create(sign * other.sign, q) to create(sign, r)
    }

    operator fun div(other: BigInt): BigInt = divideAndRemainder(other).first

    operator fun rem(other: BigInt): BigInt = divideAndRemainder(other).second

    /** Exact quotient when the remainder is known to be zero (fraction-free elimination); verifies. */
    fun divExact(other: BigInt): BigInt {
        val (q, r) = divideAndRemainder(other)
        check(r.sign == 0) { "divExact: non-zero remainder" }
        return q
    }

    fun gcd(other: BigInt): BigInt {
        if (sign == 0) return other.abs()
        if (other.sign == 0) return abs()
        return BigInt(1, gcdMag(mag, other.mag))
    }

    override fun compareTo(other: BigInt): Int {
        if (sign != other.sign) return sign.compareTo(other.sign)
        if (sign == 0) return 0
        val c = cmpMag(mag, other.mag)
        return if (sign > 0) c else -c
    }

    override fun equals(other: Any?): Boolean = other is BigInt && sign == other.sign && mag.contentEquals(other.mag)

    override fun hashCode(): Int = sign * 31 + mag.contentHashCode()

    /** Lossy conversion to Double (for forming a relaxation bound from a num/den pair). */
    fun toDouble(): Double {
        if (sign == 0) return 0.0
        var d = 0.0
        for (i in mag.indices.reversed()) d = d * TWO_POW_32 + (mag[i].toLong() and LIMB_MASK)
        return if (sign < 0) -d else d
    }

    /** Exact conversion to Long, or null if it does not fit a signed 64-bit value. */
    fun toLongOrNull(): Long? {
        if (sign == 0) return 0L
        if (mag.size > 2) return null
        val lo = mag[0].toLong() and LIMB_MASK
        val hi = if (mag.size == 2) mag[1].toLong() and LIMB_MASK else 0L
        if (hi ushr 31 != 0L) {
            // Magnitude >= 2^63; only Long.MIN_VALUE (exactly 2^63, negative) is representable.
            return if (sign < 0 && hi == (1L shl 31) && lo == 0L) Long.MIN_VALUE else null
        }
        val m = (hi shl 32) or lo // < 2^63, so non-negative
        return if (sign < 0) -m else m
    }

    override fun toString(): String {
        if (sign == 0) return "0"
        val sb = StringBuilder()
        var cur = mag
        val chunks = ArrayList<Int>()
        while (cur.isNotEmpty()) {
            val (q, r) = divmodSmall(cur, CHUNK)
            chunks.add(r)
            cur = q
        }
        if (sign < 0) sb.append('-')
        sb.append(chunks[chunks.size - 1])
        for (i in chunks.size - 2 downTo 0) {
            val s = chunks[i].toString()
            repeat(CHUNK_DIGITS - s.length) { sb.append('0') }
            sb.append(s)
        }
        return sb.toString()
    }

    companion object {
        private const val LIMB_MASK = 0xFFFFFFFFL
        private const val BASE = 1L shl 32
        private const val TWO_POW_32 = 4294967296.0
        private const val CHUNK = 1_000_000_000
        private const val CHUNK_DIGITS = 9
        private val ONE_MAG = intArrayOf(1)

        val ZERO: BigInt = BigInt(0, IntArray(0))
        val ONE: BigInt = BigInt(1, ONE_MAG)

        fun of(value: Long): BigInt {
            if (value == 0L) return ZERO
            val sign = if (value < 0) -1 else 1
            // Unsigned magnitude bits of value. For Long.MIN_VALUE, `-value` overflows, but its raw
            // bit pattern already *is* the magnitude 2^63, and ushr/and read it unsigned correctly.
            val u = if (value == Long.MIN_VALUE) {
                value
            } else if (value < 0) {
                -value
            } else {
                value
            }
            val lo = (u and LIMB_MASK).toInt()
            val hi = (u ushr 32).toInt()
            val mag = if (hi == 0) intArrayOf(lo) else intArrayOf(lo, hi)
            return BigInt(sign, mag)
        }

        /** Build from a normalized (or not) magnitude + sign; drops leading zero limbs. */
        private fun create(sign: Int, mag: IntArray): BigInt {
            var len = mag.size
            while (len > 0 && mag[len - 1] == 0) len--
            if (len == 0) return ZERO
            val m = if (len == mag.size) mag else mag.copyOf(len)
            return BigInt(sign, m)
        }

        // --- unsigned little-endian base-2^32 magnitude helpers (inputs normalized) ---

        private fun cmpMag(a: IntArray, b: IntArray): Int {
            if (a.size != b.size) return if (a.size < b.size) -1 else 1
            for (i in a.indices.reversed()) {
                val x = a[i].toLong() and LIMB_MASK
                val y = b[i].toLong() and LIMB_MASK
                if (x != y) return if (x < y) -1 else 1
            }
            return 0
        }

        private fun addMag(a: IntArray, b: IntArray): IntArray {
            val (lo, hi) = if (a.size >= b.size) a to b else b to a
            val out = IntArray(lo.size + 1)
            var carry = 0L
            for (i in lo.indices) {
                val sum = (lo[i].toLong() and LIMB_MASK) +
                    (if (i < hi.size) hi[i].toLong() and LIMB_MASK else 0L) + carry
                out[i] = sum.toInt()
                carry = sum ushr 32
            }
            out[lo.size] = carry.toInt()
            return normalize(out)
        }

        /** `a - b` assuming `a >= b`. */
        private fun subMag(a: IntArray, b: IntArray): IntArray {
            val out = IntArray(a.size)
            var borrow = 0L
            for (i in a.indices) {
                val diff = (a[i].toLong() and LIMB_MASK) -
                    (if (i < b.size) b[i].toLong() and LIMB_MASK else 0L) - borrow
                out[i] = diff.toInt()
                borrow = if (diff < 0L) 1L else 0L
            }
            return normalize(out)
        }

        private fun mulMag(a: IntArray, b: IntArray): IntArray {
            val out = IntArray(a.size + b.size)
            for (i in a.indices) {
                val ai = a[i].toLong() and LIMB_MASK
                var carry = 0L
                for (j in b.indices) {
                    val cur = (out[i + j].toLong() and LIMB_MASK) + ai * (b[j].toLong() and LIMB_MASK) + carry
                    out[i + j] = cur.toInt()
                    carry = cur ushr 32
                }
                out[i + b.size] = (out[i + b.size].toLong() and LIMB_MASK).plus(carry).toInt()
            }
            return normalize(out)
        }

        private fun normalize(mag: IntArray): IntArray {
            var len = mag.size
            while (len > 0 && mag[len - 1] == 0) len--
            return if (len == mag.size) mag else mag.copyOf(len)
        }

        private fun testBit(mag: IntArray, i: Int): Boolean = (mag[i ushr 5] ushr (i and 31)) and 1 == 1

        /**
         * Knuth Algorithm D (TAOCP 4.3.1) base-2³² long division — returns (quotient, remainder)
         * magnitudes. Caller guarantees `|a| > |b| > 0`. The multiply-subtract step uses the
         * Hacker's Delight borrow formulation; intermediate products are 64-bit unsigned.
         */
        private fun divmodMag(a: IntArray, b: IntArray): Pair<IntArray, IntArray> {
            val n = b.size
            if (n == 1) {
                val (q, r) = divmodSmall(a, b[0])
                return q to (if (r == 0) IntArray(0) else intArrayOf(r))
            }
            val m = a.size - n
            // D1: normalize so the divisor's top limb has its high bit set.
            val shift = b[n - 1].countLeadingZeroBits()
            val v = shlSmall(b, shift) // top limb's high bit set ⇒ no carry-out ⇒ length stays n
            val u = IntArray(a.size + 1)
            shlSmall(a, shift).let { for (i in it.indices) u[i] = it[i] }
            val q = IntArray(m + 1)
            val vTop = v[n - 1].toLong() and LIMB_MASK
            val vSub = v[n - 2].toLong() and LIMB_MASK
            for (j in m downTo 0) {
                val num = ((u[j + n].toLong() and LIMB_MASK) shl 32) or (u[j + n - 1].toLong() and LIMB_MASK)
                var qhat = (num.toULong() / vTop.toULong()).toLong()
                var rhat = (num.toULong() % vTop.toULong()).toLong()
                val u2 = u[j + n - 2].toLong() and LIMB_MASK
                while (qhat >= BASE || qhat.toULong() * vSub.toULong() > ((rhat shl 32) or u2).toULong()) {
                    qhat--
                    rhat += vTop
                    if (rhat >= BASE) break
                }
                // D4: multiply qhat·v and subtract from u[j..j+n].
                var k = 0L
                for (i in 0 until n) {
                    val p = qhat.toULong() * (v[i].toLong() and LIMB_MASK).toULong()
                    val t = (u[j + i].toLong() and LIMB_MASK) - k - (p and 0xFFFFFFFFuL).toLong()
                    u[j + i] = t.toInt()
                    k = (p shr 32).toLong() - (t shr 32)
                }
                val t = (u[j + n].toLong() and LIMB_MASK) - k
                u[j + n] = t.toInt()
                // D5/D6: a negative result means qhat was 1 too high — decrement and add v back.
                if (t < 0L) {
                    qhat--
                    var c = 0L
                    for (i in 0 until n) {
                        val s = (u[j + i].toLong() and LIMB_MASK) + (v[i].toLong() and LIMB_MASK) + c
                        u[j + i] = s.toInt()
                        c = s ushr 32
                    }
                    u[j + n] = ((u[j + n].toLong() and LIMB_MASK) + c).toInt()
                }
                q[j] = qhat.toInt()
            }
            return normalize(q) to shrSmall(normalize(u.copyOf(n)), shift)
        }

        /** Shift a magnitude left by `k` bits (`0 ≤ k < 32`). */
        private fun shlSmall(mag: IntArray, k: Int): IntArray {
            if (k == 0 || mag.isEmpty()) return mag
            val out = IntArray(mag.size + 1)
            var carry = 0L
            for (i in mag.indices) {
                val v = ((mag[i].toLong() and LIMB_MASK) shl k) or carry
                out[i] = v.toInt()
                carry = v ushr 32
            }
            out[mag.size] = carry.toInt()
            return normalize(out)
        }

        /** Shift a magnitude right by `k` bits (`0 ≤ k < 32`). */
        private fun shrSmall(mag: IntArray, k: Int): IntArray {
            if (k == 0 || mag.isEmpty()) return mag
            val out = IntArray(mag.size)
            for (i in mag.indices) {
                val lo = (mag[i].toLong() and LIMB_MASK) ushr k
                val hi = if (i + 1 < mag.size) (mag[i + 1].toLong() and LIMB_MASK) shl (32 - k) else 0L
                out[i] = (lo or hi).toInt()
            }
            return normalize(out)
        }

        /** Multiply a magnitude by 2 (shift left one bit). */
        private fun shlOne(mag: IntArray): IntArray {
            if (mag.isEmpty()) return mag
            val out = IntArray(mag.size + 1)
            var carry = 0L
            for (i in mag.indices) {
                val v = ((mag[i].toLong() and LIMB_MASK) shl 1) or carry
                out[i] = v.toInt()
                carry = v ushr 32
            }
            out[mag.size] = carry.toInt()
            return normalize(out)
        }

        /** Divide a magnitude by a small positive divisor; returns (quotient mag, remainder). */
        private fun divmodSmall(mag: IntArray, divisor: Int): Pair<IntArray, Int> {
            // Unsigned 64-bit: a single-limb divisor with its high bit set (≥ 2³¹) makes
            // `rem·2³² + limb` exceed signed-Long range, so ULong is required.
            val d = (divisor.toLong() and LIMB_MASK).toULong()
            val q = IntArray(mag.size)
            var rem = 0uL
            for (i in mag.indices.reversed()) {
                val cur = (rem shl 32) or (mag[i].toLong() and LIMB_MASK).toULong()
                q[i] = (cur / d).toInt()
                rem = cur % d
            }
            return normalize(q) to rem.toInt()
        }

        /** Binary (Stein's) GCD on non-empty magnitudes. */
        private fun gcdMag(aIn: IntArray, bIn: IntArray): IntArray {
            var a = aIn
            var b = bIn
            var shift = 0
            while (!testBit(a, 0) && !testBit(b, 0)) {
                a = shrOne(a)
                b = shrOne(b)
                shift++
            }
            while (!testBit(a, 0)) a = shrOne(a)
            while (b.isNotEmpty()) {
                while (!testBit(b, 0)) b = shrOne(b)
                if (cmpMag(a, b) > 0) {
                    val t = a
                    a = b
                    b = t
                }
                b = subMag(b, a)
            }
            var r = a
            repeat(shift) { r = shlOne(r) }
            return r
        }

        /** Divide a magnitude by 2 (shift right one bit). */
        private fun shrOne(mag: IntArray): IntArray {
            if (mag.isEmpty()) return mag
            val out = IntArray(mag.size)
            var carry = 0L
            for (i in mag.indices.reversed()) {
                val v = (mag[i].toLong() and LIMB_MASK) or (carry shl 32)
                out[i] = (v ushr 1).toInt()
                carry = v and 1L
            }
            return normalize(out)
        }
    }
}
