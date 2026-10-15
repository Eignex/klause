package com.eignex.klause.lowering

import com.eignex.klause.factor.arithmetic.DivMod
import com.eignex.klause.factor.arithmetic.DivisionConvention
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.abs
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.maxOf
import com.eignex.klause.util.minOf
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.toLongExact

internal class DivModRelation(val quotient: Int, val remainder: Int, val factors: List<Factor>)

internal fun retainDivMod(
    dividend: Int,
    divisor: Int,
    dividendDomain: IntDomain,
    divisorDomain: IntDomain,
    quotient: Int?,
    remainder: Int?,
    existing: List<Factor>,
    freshInt: (IntDomain) -> Int,
    convention: DivisionConvention = DivisionConvention.TRUNCATING,
): DivModRelation {
    val constant = divisorDomain.takeIf { it.min == it.max }?.min?.let(::bigIntOf)
    val retained = existing.asReversed().firstOrNull { factor ->
        factor is DivMod && factor.dividend == dividend && factor.convention == convention &&
            if (constant != null) factor.constantDivisor == constant else factor.divisor == divisor
    } as? DivMod
    if (retained != null) {
        val aliases = ArrayList<Factor>(2)
        fun link(output: Int?, retainedOutput: Int) {
            if (output != null && output != retainedOutput) {
                aliases += Linear(intArrayOf(1, -1), intArrayOf(output, retainedOutput), LinearOp.EQ, 0)
            }
        }
        link(quotient, retained.quotient)
        link(remainder, retained.remainder)
        return DivModRelation(retained.quotient, retained.remainder, aliases)
    }
    val aMagnitude = maxOf(bigIntOf(dividendDomain.min).abs(), bigIntOf(dividendDomain.max).abs())
    val bMagnitude = maxOf(bigIntOf(divisorDomain.min).abs(), bigIntOf(divisorDomain.max).abs())
    fun bounded(lower: BigInt, upper: BigInt): IntDomain {
        val lo = maxOf(bigIntOf(Long.MIN_VALUE), minOf(bigIntOf(Long.MAX_VALUE), lower))
        val hi = maxOf(bigIntOf(Long.MIN_VALUE), minOf(bigIntOf(Long.MAX_VALUE), upper))
        return IntDomain(lo.toLongExact(), hi.toLongExact())
    }
    val qMagnitude = aMagnitude + BIG_ONE
    val q = quotient ?: freshInt(bounded(BIG_ZERO - qMagnitude, qMagnitude))
    val rMagnitude = maxOf(BIG_ZERO, bMagnitude - BIG_ONE)
    val lowerR = if (convention == DivisionConvention.EUCLIDEAN || dividendDomain.min >= 0L)
        BIG_ZERO else BIG_ZERO - rMagnitude
    val upperR = if (convention == DivisionConvention.TRUNCATING && dividendDomain.max <= 0L) BIG_ZERO else rMagnitude
    val r = remainder ?: freshInt(bounded(lowerR, upperR))
    val factor = if (constant != null) DivMod(dividend, constant, q, r, convention)
        else DivMod(dividend, divisor, q, r, convention)
    return DivModRelation(q, r, listOf(factor))
}
