package com.eignex.klause.util

/**
 * Minimal immutable arbitrary-precision signed integer for the exact rational LP fallback.
 * Kotlin-Multiplatform common code has no platform big integer, and the exact simplex needs one:
 * pivot chains blow past any fixed-width accumulator, and bailing on overflow would forfeit the
 * verdicts the fallback exists to rescue.
 *
 * Representation: [sign] in {-1, 0, 1} and a little-endian base-2^32 magnitude with no leading
 * zero words ([sign] == 0 iff the magnitude is empty). Operations are schoolbook — the fallback's
 * models are small and exactness, not asymptotics, is the point.
 */
internal class BigInt private constructor(val sign: Int, private val mag: IntArray) : Comparable<BigInt> {

    val isZero: Boolean get() = sign == 0

    val isNegative: Boolean get() = sign < 0

    operator fun unaryMinus(): BigInt = if (sign == 0) this else BigInt(-sign, mag)

    operator fun plus(other: BigInt): BigInt = when {
        sign == 0 -> other

        other.sign == 0 -> this

        sign == other.sign -> BigInt(sign, addMag(mag, other.mag))

        else -> {
            val c = compareMag(mag, other.mag)
            when {
                c == 0 -> ZERO
                c > 0 -> BigInt(sign, subMag(mag, other.mag))
                else -> BigInt(other.sign, subMag(other.mag, mag))
            }
        }
    }

    operator fun minus(other: BigInt): BigInt = this + (-other)

    operator fun times(other: BigInt): BigInt {
        if (sign == 0 || other.sign == 0) return ZERO
        return BigInt(sign * other.sign, mulMag(mag, other.mag))
    }

    /** Truncated quotient and remainder (remainder takes this dividend's sign). */
    fun divRem(divisor: BigInt): Pair<BigInt, BigInt> {
        require(divisor.sign != 0) { "division by zero" }
        val c = compareMag(mag, divisor.mag)
        if (sign == 0 || c < 0) return ZERO to this
        if (c == 0) return fromLong((sign * divisor.sign).toLong()) to ZERO
        val (q, r) = divRemMag(mag, divisor.mag)
        val quotient = if (isEmptyMag(q)) ZERO else BigInt(sign * divisor.sign, q)
        val remainder = if (isEmptyMag(r)) ZERO else BigInt(sign, r)
        return quotient to remainder
    }

    /** Greatest common divisor of the magnitudes (always non-negative; gcd(0, x) = |x|). */
    fun gcd(other: BigInt): BigInt {
        var a = this.abs()
        var b = other.abs()
        while (!b.isZero) {
            val r = a.divRem(b).second.abs()
            a = b
            b = r
        }
        return a
    }

    fun abs(): BigInt = if (sign < 0) BigInt(1, mag) else this

    override fun compareTo(other: BigInt): Int {
        if (sign != other.sign) return sign.compareTo(other.sign)
        if (sign == 0) return 0
        return sign * compareMag(mag, other.mag)
    }

    override fun equals(other: Any?): Boolean = other is BigInt && sign == other.sign && mag.contentEquals(other.mag)

    override fun hashCode(): Int = sign * 31 + mag.contentHashCode()

    /** The value as an exact Long, or null when it does not fit. */
    fun toLongOrNull(): Long? {
        if (sign == 0) return 0L
        if (mag.size > 2) return null
        val lo = mag[0].toLong() and 0xFFFFFFFFL
        val hi = if (mag.size == 2) mag[1].toLong() and 0xFFFFFFFFL else 0L
        val v = (hi shl 32) or lo
        if (hi >= 0x80000000L) {
            // Magnitude at or above 2^63: only Long.MIN_VALUE is representable.
            return if (sign < 0 && hi == 0x80000000L && lo == 0L) Long.MIN_VALUE else null
        }
        return sign * v
    }

    /** The value as a Double (rounded); infinite when far outside range. Diagnostic use only. */
    fun toDouble(): Double {
        var r = 0.0
        for (i in mag.indices.reversed()) r = r * 4294967296.0 + (mag[i].toLong() and 0xFFFFFFFFL)
        return sign * r
    }

    override fun toString(): String {
        if (sign == 0) return "0"
        val sb = StringBuilder()
        var cur = abs()
        val ten = fromLong(10L)
        while (!cur.isZero) {
            val (q, r) = cur.divRem(ten)
            sb.append('0' + (r.toLongOrNull() ?: 0L).toInt())
            cur = q
        }
        if (sign < 0) sb.append('-')
        return sb.reverse().toString()
    }

    companion object {
        val ZERO = BigInt(0, IntArray(0))
        val ONE = fromLong(1L)

        fun fromLong(v: Long): BigInt {
            if (v == 0L) return ZERO
            val sign = if (v < 0L) -1 else 1
            // Two's-complement magnitude; correct for Long.MIN_VALUE as well.
            val m = if (v < 0L) v.toULong().inv() + 1uL else v.toULong()
            val lo = (m and 0xFFFFFFFFuL).toInt()
            val hi = (m shr 32).toInt()
            return BigInt(sign, if (hi == 0) intArrayOf(lo) else intArrayOf(lo, hi))
        }

        private fun isEmptyMag(m: IntArray): Boolean = m.isEmpty()

        private fun word(m: IntArray, i: Int): Long = if (i < m.size) m[i].toLong() and 0xFFFFFFFFL else 0L

        private fun trim(m: IntArray): IntArray {
            var top = m.size
            while (top > 0 && m[top - 1] == 0) top--
            return if (top == m.size) m else m.copyOf(top)
        }

        private fun compareMag(a: IntArray, b: IntArray): Int {
            if (a.size != b.size) return a.size.compareTo(b.size)
            for (i in a.indices.reversed()) {
                val c = (a[i].toLong() and 0xFFFFFFFFL).compareTo(b[i].toLong() and 0xFFFFFFFFL)
                if (c != 0) return c
            }
            return 0
        }

        private fun addMag(a: IntArray, b: IntArray): IntArray {
            val n = maxOf(a.size, b.size)
            val out = IntArray(n + 1)
            var carry = 0L
            for (i in 0 until n) {
                val s = word(a, i) + word(b, i) + carry
                out[i] = s.toInt()
                carry = s ushr 32
            }
            out[n] = carry.toInt()
            return trim(out)
        }

        /** a - b for magnitudes with a >= b. */
        private fun subMag(a: IntArray, b: IntArray): IntArray {
            val out = IntArray(a.size)
            var borrow = 0L
            for (i in a.indices) {
                val d = word(a, i) - word(b, i) - borrow
                out[i] = d.toInt()
                borrow = if (d < 0L) 1L else 0L
            }
            return trim(out)
        }

        private fun mulMag(a: IntArray, b: IntArray): IntArray {
            val out = IntArray(a.size + b.size)
            for (i in a.indices) {
                var carry = 0L
                val ai = word(a, i)
                for (j in b.indices) {
                    val t = ai * word(b, j) + (out[i + j].toLong() and 0xFFFFFFFFL) + carry
                    out[i + j] = t.toInt()
                    carry = t ushr 32
                }
                var k = i + b.size
                while (carry != 0L) {
                    val t = (out[k].toLong() and 0xFFFFFFFFL) + carry
                    out[k] = t.toInt()
                    carry = t ushr 32
                    k++
                }
            }
            return trim(out)
        }

        /** Schoolbook binary long division over magnitudes; a > b, b non-zero. */
        private fun divRemMag(a: IntArray, b: IntArray): Pair<IntArray, IntArray> {
            val bitsA = bitLength(a)
            var rem = IntArray(0)
            val quot = IntArray(a.size)
            for (bit in bitsA - 1 downTo 0) {
                rem = shiftLeftOne(rem)
                if (testBit(a, bit)) rem = setBitZero(rem)
                if (compareMag(rem, b) >= 0) {
                    rem = subMag(rem, b)
                    quot[bit ushr 5] = quot[bit ushr 5] or (1 shl (bit and 31))
                }
            }
            return trim(quot) to trim(rem)
        }

        private fun bitLength(m: IntArray): Int {
            if (m.isEmpty()) return 0
            return (m.size - 1) * 32 + (32 - m[m.size - 1].countLeadingZeroBits())
        }

        private fun testBit(m: IntArray, bit: Int): Boolean {
            val w = bit ushr 5
            return w < m.size && (m[w] shr (bit and 31)) and 1 == 1
        }

        private fun shiftLeftOne(m: IntArray): IntArray {
            val out = IntArray(m.size + 1)
            var carry = 0
            for (i in m.indices) {
                out[i] = (m[i] shl 1) or carry
                carry = m[i] ushr 31
            }
            out[m.size] = carry
            return trim(out)
        }

        /** Set bit zero of the (already shifted) magnitude. */
        private fun setBitZero(m: IntArray): IntArray {
            if (m.isEmpty()) return intArrayOf(1)
            val out = m.copyOf()
            out[0] = out[0] or 1
            return out
        }
    }
}
