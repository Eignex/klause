package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.IntVars
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.hashRemappedKey
import com.eignex.klause.ir.materializeKey
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.abs
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.div
import com.eignex.klause.util.isZero
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.signum
import com.eignex.klause.util.times

/** The quotient rounding and remainder sign of an integer division relation. */
enum class DivisionConvention {
    /** Quotient rounds toward zero; a nonzero remainder has the dividend's sign. */
    TRUNCATING,
    /** Remainder is nonnegative and smaller than the divisor's magnitude. */
    EUCLIDEAN,
}

/**
 * Correlated integer quotient and remainder: `dividend = divisor * quotient + remainder`.
 * The divisor must be nonzero. Arithmetic is exact, including a quotient beyond a signed word;
 * a finite consumer rejects that quotient when its declared output cannot hold it.
 * Repeated variable roles remain simultaneous equalities after remapping.
 */
class DivMod private constructor(
    /** Dividend coordinate. */
    val dividend: Int,
    /** Divisor coordinate, absent for a literal divisor. */
    val divisor: Int?,
    /** Exact literal divisor, absent for a variable divisor. */
    val constantDivisor: BigInt?,
    /** Quotient coordinate. */
    val quotient: Int,
    /** Remainder coordinate. */
    val remainder: Int,
    /** Source rounding convention. */
    val convention: DivisionConvention,
) : Factor {
    /** Construct division by an integer source coordinate. */
    constructor(dividend: Int, divisor: Int, quotient: Int, remainder: Int,
        convention: DivisionConvention = DivisionConvention.TRUNCATING) :
        this(dividend, divisor, null, quotient, remainder, convention)

    /** Construct division by an exact integer literal, including arbitrary-width constants. */
    constructor(dividend: Int, divisor: BigInt, quotient: Int, remainder: Int,
        convention: DivisionConvention = DivisionConvention.TRUNCATING) :
        this(dividend, null, divisor, quotient, remainder, convention)

    override val variables: VarList = IntVars(if (divisor == null) intArrayOf(dividend, quotient, remainder)
        else intArrayOf(dividend, divisor, quotient, remainder))

    override fun remap(mapping: VarRemap): Factor = DivMod(mapping.int(dividend), divisor?.let(mapping::int),
        constantDivisor, mapping.int(quotient), mapping.int(remainder), convention)

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.DIV_MOD, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int = hashRemappedKey(FactorKind.DIV_MOD, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.enum(convention)
        sink.intVar(dividend)
        sink.bool(divisor != null)
        if (divisor != null) sink.intVar(divisor)
        else sink.constInts(checkNotNull(constantDivisor).toString().map { it.code }.toIntArray())
        sink.intVar(quotient)
        sink.intVar(remainder)
    }

    internal fun wordOutputs(a: Long, b: Long): Pair<Long, Long>? {
        if (b == 0L || (a == Long.MIN_VALUE && b == -1L)) return null
        var quotient = a / b
        var remainder = a % b
        if (convention == DivisionConvention.EUCLIDEAN && remainder < 0L) {
            if (b > 0L) {
                quotient--
                remainder += b
            } else {
                quotient++
                remainder -= b
            }
        }
        return quotient to remainder
    }

    internal fun outputs(a: BigInt, b: BigInt): Pair<BigInt, BigInt>? = integerDivMod(a, b, convention)
}

internal fun integerDivMod(a: BigInt, b: BigInt, convention: DivisionConvention): Pair<BigInt, BigInt>? {
    if (b.isZero()) return null
    var q = a / b
    var r = a - b * q
    if (convention == DivisionConvention.EUCLIDEAN && r < BIG_ZERO) {
        q -= bigIntOf(b.signum())
        r += b.abs()
    }
    return q to r
}
