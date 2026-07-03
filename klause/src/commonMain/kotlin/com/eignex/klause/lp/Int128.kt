package com.eignex.klause.lp

/**
 * Minimal signed 128-bit integer accumulator for the integer-multiplier LP certification. It is
 * the Kotlin-Multiplatform stand-in for a wider integer: the dual-bound recomputation sums
 * products of two 64-bit integers, where each product fits in 64 bits (the multipliers are scaled to
 * keep them small) but the *running sum* overflows it. A two-`Long` (hi · 2⁶⁴ + lo) value covers that,
 * with no exotic intrinsic — carry detection uses the common-stdlib unsigned compare, and the one
 * 64×64→128 product needed for [addProduct] is a portable 32-bit schoolbook expansion, so a single
 * code path serves JVM, Native and wasm.
 *
 * Represented in two's complement: the true value is `hi * 2⁶⁴ + (lo as unsigned)`. The accumulator is
 * mutable and allocation-light (one instance reused across a bound computation); [overflow] latches if
 * an [addProduct]/[addLong] ever carries out of 128 bits, after which the result is meaningless and the
 * caller must discard it (keeping the node — sound, only a missed prune).
 *
 * Soundness contract: every operation is exact unless [overflow] latches; callers treat a latched
 * accumulator as a failed deduction (no prune / no cut), exactly like a non-finite float bound.
 */
internal class Int128 {
    /** High 64 bits (signed). */
    var hi: Long = 0L
        private set

    /** Low 64 bits, interpreted as unsigned. */
    var lo: Long = 0L
        private set

    /** Latched true once an add carried out of the 128-bit range; the value is then unusable. */
    var overflow: Boolean = false
        private set

    /** Reset to zero (reuse across bound computations). */
    fun clear() {
        hi = 0L
        lo = 0L
        overflow = false
    }

    /** Add the signed 64-bit [v]. */
    fun addLong(v: Long) {
        add128(v shr 63, v)
    }

    /** Add the exact 128-bit product `a · b` of two signed 64-bit integers. */
    fun addProduct(a: Long, b: Long) {
        if (a == 0L || b == 0L) return
        // Unsigned magnitudes of |a|, |b| (0UL - x.toULong() is the two's-complement magnitude and is
        // correct even for Long.MIN_VALUE, where the magnitude is exactly 2⁶³).
        val ua = if (a < 0L) 0uL - a.toULong() else a.toULong()
        val ub = if (b < 0L) 0uL - b.toULong() else b.toULong()
        // Unsigned 64×64 → 128 schoolbook over 32-bit halves.
        val aLo = ua and 0xFFFFFFFFuL
        val aHi = ua shr 32
        val bLo = ub and 0xFFFFFFFFuL
        val bHi = ub shr 32
        val ll = aLo * bLo
        val lh = aLo * bHi
        val hl = aHi * bLo
        val hh = aHi * bHi
        val cross = (ll shr 32) + (lh and 0xFFFFFFFFuL) + (hl and 0xFFFFFFFFuL)
        val prodLo = (cross shl 32) or (ll and 0xFFFFFFFFuL)
        val prodHi = hh + (lh shr 32) + (hl shr 32) + (cross shr 32)
        // Apply the product's sign, then add the 128-bit value.
        if ((a < 0L) != (b < 0L)) {
            // Negate (prodHi, prodLo): two's complement of the 128-bit magnitude.
            val negLo = (0uL - prodLo)
            val borrow = if (prodLo != 0uL) 1uL else 0uL
            val negHi = (prodHi.inv() + (1uL - borrow))
            add128(negHi.toLong(), negLo.toLong())
        } else {
            add128(prodHi.toLong(), prodLo.toLong())
        }
    }

    /** Add the 128-bit value `bHi · 2⁶⁴ + (bLo as unsigned)`, latching [overflow] on signed-128 wrap. */
    private fun add128(bHi: Long, bLo: Long) {
        val newLo = lo + bLo
        // Unsigned carry out of the low word: the sum wrapped below either addend.
        val carry = if (newLo.toULong() < lo.toULong()) 1L else 0L
        val oldHi = hi
        val newHi = oldHi + bHi + carry
        // Signed overflow of the high word (with carry folded in) ⇒ the 128-bit add overflowed.
        if (((oldHi xor newHi) and (bHi xor newHi)) < 0L) overflow = true
        lo = newLo
        hi = newHi
    }

    /** Add another 128-bit value (propagating its [overflow] latch). */
    fun add(other: Int128) {
        add128(other.hi, other.lo)
        if (other.overflow) overflow = true
    }

    /** Subtract another 128-bit value (propagating its [overflow] latch). */
    fun subtract(other: Int128) {
        // Add the two's-complement negation of `other`'s 128-bit value.
        val negLo = 0uL - other.lo.toULong()
        val borrow = if (other.lo != 0L) 1uL else 0uL
        val negHi = other.hi.toULong().inv() + (1uL - borrow)
        add128(negHi.toLong(), negLo.toLong())
        if (other.overflow) overflow = true
    }

    /** An independent copy of this value (including the [overflow] latch). */
    fun copy(): Int128 = Int128().also {
        it.hi = hi
        it.lo = lo
        it.overflow = overflow
    }

    /** True when the value is `≥ 0` (and [overflow] never latched). The signed-128 sign is the top bit
     *  of [hi], so non-negativity is simply `hi ≥ 0`. */
    fun isNonNegative(): Boolean = !overflow && hi >= 0L

    /** Multiply by `2ᵏ` in place ([bits] in `0..62`), latching [overflow] if the result escapes the
     *  signed 128-bit range. Used to bring power-of-two-scaled rationals to a common denominator. */
    fun shiftLeft(bits: Int) {
        require(bits in 0..62) { "shift out of range: $bits" }
        if (bits == 0 || overflow) return
        val ulo = lo.toULong()
        val newLo = (ulo shl bits).toLong()
        val newHi = (hi shl bits) or (ulo shr (64 - bits)).toLong()
        // No overflow iff the bits shifted off the top of `hi` were a pure sign extension of the result.
        if ((hi shr (64 - bits)) != (newHi shr 63)) overflow = true
        hi = newHi
        lo = newLo
    }

    /** True when the value fits a signed 64-bit `Long` (and [overflow] never latched). */
    fun fitsLong(): Boolean = !overflow && hi == (lo shr 63)

    /** The value as a `Long`; valid only when [fitsLong]. */
    fun toLong(): Long = lo

    /**
     * `⌈ value / 2ᵏ ⌉` as a `Long`, or null when [overflow] latched or the quotient does not fit a
     * `Long`. Uses `ceil(x) = −⌊−x⌋` and an arithmetic right shift for the floored division by a power
     * of two — exact and division-free. [k] must be in `0..62`.
     */
    fun ceilDivPow2(k: Int): Long? {
        if (overflow) return null
        require(k in 0..62) { "shift out of range: $k" }
        if (k == 0) return if (fitsLong()) lo else null
        // Negate to (nHi, nLo), arithmetic-shift right by k (floor of the negated value), negate back.
        val nLo = 0uL - lo.toULong()
        val borrow = if (lo != 0L) 1uL else 0uL
        val nHi = hi.toULong().inv() + (1uL - borrow)
        // Arithmetic shift right of the 128-bit (nHi signed, nLo unsigned) by k < 64.
        val shLo = ((nLo shr k) or (nHi shl (64 - k))).toLong()
        val shHi = nHi.toLong() shr k // arithmetic (sign-propagating)
        // floor(−value / 2ᵏ) = (shHi, shLo); ceil(value / 2ᵏ) = −that. Require it fit a Long first.
        if (shHi != (shLo shr 63)) return null
        // Negate the Long result (safe unless it is Long.MIN_VALUE, which then cannot be a Long anyway).
        if (shLo == Long.MIN_VALUE) return null
        return -shLo
    }

    /**
     * `⌊ value / d ⌋` for a **positive** divisor [d], as a `Long`, or null when [overflow] latched or the
     * quotient does not fit a `Long`. Exact floored (toward −∞) division — used for the reduced-cost
     * fixing step count, where the quotient must be a sound (never-too-large) integer bound. The
     * unsigned 128÷64 inner division is a restoring bit-by-bit long division; it relies on `d ≤ 2⁶³−1`
     * (every positive `Long`) so the running remainder stays below `2⁶³` and never overflows.
     */
    fun floorDivPositive(d: Long): Long? {
        require(d > 0L) { "divisor must be positive: $d" }
        if (overflow) return null
        // Fast path: a dividend that already fits a `Long` needs only native floored division. Its
        // quotient is no larger in magnitude, so it always fits a `Long`, and the result is identical to
        // the 128-bit restoring loop (validated by the `BigInteger` oracle). This is the common case in
        // reduced-cost fixing — most reduced-cost numerators are well within 64 bits — so it avoids the
        // unconditional 128-iteration loop on every nonbasic column.
        if (fitsLong()) return lo.floorDiv(d)
        val negative = hi < 0L
        val uLo: ULong
        val uHi: ULong
        if (negative) {
            uLo = 0uL - lo.toULong()
            val borrow = if (lo != 0L) 1uL else 0uL
            uHi = hi.toULong().inv() + (1uL - borrow)
        } else {
            uLo = lo.toULong()
            uHi = hi.toULong()
        }
        val dd = d.toULong()
        var qHi = 0uL
        var qLo = 0uL
        var rem = 0uL
        for (bit in 127 downTo 0) {
            rem = rem shl 1
            val nextBit = if (bit >= 64) (uHi shr (bit - 64)) and 1uL else (uLo shr bit) and 1uL
            rem = rem or nextBit
            if (rem >= dd) {
                rem -= dd
                if (bit >= 64) qHi = qHi or (1uL shl (bit - 64)) else qLo = qLo or (1uL shl bit)
            }
        }
        if (qHi != 0uL) return null // quotient exceeds 64 bits ⇒ does not fit a Long
        if (!negative) return if (qLo <= Long.MAX_VALUE.toULong()) qLo.toLong() else null
        // Floor of a negative dividend rounds the magnitude up when there is a remainder.
        val mag = if (rem == 0uL) qLo else qLo + 1uL
        return when {
            mag <= Long.MAX_VALUE.toULong() -> -(mag.toLong())
            mag == Long.MAX_VALUE.toULong() + 1uL -> Long.MIN_VALUE
            else -> null
        }
    }
}
