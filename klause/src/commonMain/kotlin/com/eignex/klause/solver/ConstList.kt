package com.eignex.klause.solver

import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * The constants one factor holds — the coefficients of a weighted sum, the weights of an objective.
 *
 * The width is the type. A consumer that reasons in 64-bit integers narrows to [LongConstList] once and
 * then reads terms without a per-term guard; a consumer that cannot handle a wider constant sees the
 * narrowing fail instead of reading a saturated placeholder. Storage follows the same axis: all-one
 * coefficients keep no per-term storage at all ([UnitConsts]), and integral ones that fit 32 bits keep
 * 4 bytes per term ([IntConsts]) rather than 8.
 */
sealed interface ConstList {
    /** Number of constants. */
    val size: Int
}

/** Constants that all fit [Long] — the only form integer reasoning may read term by term. */
sealed interface LongConstList : ConstList {
    /** The constant at [index]. */
    fun at(index: Int): Long

    /** Largest `|constant|`, 0 when empty. */
    val maxAbs: Long

    /** A fresh [LongArray] of every constant, for a whole-array sink; prefer [at] for indexed reads. */
    fun toLongArray(): LongArray

    /** The same constants with every sign flipped — the `>=` to `<=` canonicalisation of a row. */
    fun negated(): LongConstList
}

/** Every constant is 1, so no per-term storage is kept: a clause-shaped row, a cardinality bound. */
class UnitConsts(override val size: Int) : LongConstList {
    override fun at(index: Int): Long = 1L

    override val maxAbs: Long get() = if (size == 0) 0L else 1L

    override fun toLongArray(): LongArray = LongArray(size) { 1L }

    override fun negated(): LongConstList = IntConsts(IntArray(size) { -1 })
}

/** Integral constants within the 32-bit range, at 4 bytes per term. */
class IntConsts(private val values: IntArray) : LongConstList {
    override val size: Int get() = values.size

    override val maxAbs: Long = maxAbsOf(values)

    override fun at(index: Int): Long = values[index].toLong()

    override fun toLongArray(): LongArray = LongArray(values.size) { values[it].toLong() }

    // Widens through [constsOf] rather than negating in place: `-Int.MIN_VALUE` does not fit an [Int].
    override fun negated(): LongConstList = constsOf(LongArray(values.size) { -values[it].toLong() })
}

/** Integral constants that need the full 64-bit range. */
class LongConsts(private val values: LongArray) : LongConstList {
    override val size: Int get() = values.size

    override val maxAbs: Long = maxAbsOf(values)

    override fun at(index: Int): Long = values[index]

    override fun toLongArray(): LongArray = values.copyOf()

    override fun negated(): LongConstList = LongConsts(LongArray(values.size) { -values[it] })
}

/** Integral constants beyond the 64-bit range, carried exactly. Never narrows to [LongConstList], so a
 *  64-bit consumer cannot read one by accident. */
class WideConsts(private val values: Array<BigInteger>) : ConstList {
    override val size: Int get() = values.size

    /** The constant at [index]. */
    fun at(index: Int): BigInteger = values[index]

    /** A fresh array of every constant. */
    fun toTypedArray(): Array<BigInteger> = values.copyOf()

    /** The same constants with every sign flipped. */
    fun negated(): WideConsts = WideConsts(Array(values.size) { -values[it] })
}

/** Continuous constants. Never narrows to [LongConstList]: a fractional coefficient has no integer
 *  reading, so a row carrying one is decided by the relaxation and the exact leaf. */
class RealConsts(private val values: DoubleArray) : ConstList {
    override val size: Int get() = values.size

    /** The constant at [index]. */
    fun at(index: Int): Double = values[index]

    /** A fresh array of every constant. */
    fun toDoubleArray(): DoubleArray = values.copyOf()

    /** The same constants with every sign flipped. */
    fun negated(): RealConsts = RealConsts(DoubleArray(values.size) { -values[it] })
}

/** The narrowest [LongConstList] holding [values]. */
fun constsOf(values: LongArray): LongConstList {
    var allOne = true
    var fitsInt = true
    for (v in values) {
        if (v != 1L) allOne = false
        if (v < Int.MIN_VALUE.toLong() || v > Int.MAX_VALUE.toLong()) fitsInt = false
    }
    return when {
        allOne -> UnitConsts(values.size)
        fitsInt -> IntConsts(IntArray(values.size) { values[it].toInt() })
        else -> LongConsts(values.copyOf())
    }
}

/** The narrowest [LongConstList] holding [values]. */
fun constsOf(values: IntArray): LongConstList {
    for (v in values) if (v != 1) return IntConsts(values.copyOf())
    return UnitConsts(values.size)
}

/** This list read as 64-bit integers, or `null` when its constants are wider than that. */
fun ConstList.longsOrNull(): LongConstList? = this as? LongConstList

/** No constants. */
val NoConsts: LongConstList = UnitConsts(0)

private fun maxAbsOf(values: IntArray): Long {
    var max = 0L
    for (v in values) {
        val a = if (v < 0) -v.toLong() else v.toLong()
        if (a > max) max = a
    }
    return max
}

private fun maxAbsOf(values: LongArray): Long {
    var max = 0L
    for (v in values) {
        val a = if (v == Long.MIN_VALUE) {
            Long.MAX_VALUE
        } else if (v < 0L) {
            -v
        } else {
            v
        }
        if (a > max) max = a
    }
    return max
}
