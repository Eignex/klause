package com.eignex.klause.solver.lp

/**
 * Minimal signed 128-bit integer accumulator for the integer-multiplier LP certification (#B0). It is
 * the Kotlin-Multiplatform stand-in for CP-SAT's `absl::int128`: the dual-bound recomputation sums
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
 * accumulator exactly like the `BigRational` certifier's `null` (no deduction).
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
}
