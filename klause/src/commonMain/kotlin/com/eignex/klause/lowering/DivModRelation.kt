package com.eignex.klause.lowering

import com.eignex.klause.factor.arithmetic.DivMod
import com.eignex.klause.factor.arithmetic.DivisionConvention
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.abs
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.maxOf
import com.eignex.util.numbers.minOf
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.toLongExact

internal class DivModRelation(val quotient: Int, val remainder: Int, val factors: List<Factor>)

internal class DivModRelationIndex {
    private data class Key(
        val dividend: Int,
        val divisor: Int?,
        val constantDivisor: Long?,
        val convention: DivisionConvention,
    )

    private val relations = HashMap<Key, DivMod>()

    fun retain(
        dividend: Int,
        divisor: Int,
        dividendDomain: IntDomain,
        divisorDomain: IntDomain,
        quotient: Int?,
        remainder: Int?,
        freshInt: (IntDomain) -> Int,
        convention: DivisionConvention = DivisionConvention.TRUNCATING,
    ): DivModRelation {
        val constant = divisorDomain.takeIf { it.min == it.max }?.min
        val key = Key(dividend, if (constant == null) divisor else null, constant, convention)
        val retained = relations[key]
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
        val lowerR = if (convention == DivisionConvention.EUCLIDEAN || dividendDomain.min >= 0L) {
            BIG_ZERO
        } else {
            BIG_ZERO - rMagnitude
        }
        val upperR = if (convention == DivisionConvention.TRUNCATING && dividendDomain.max <= 0L) {
            BIG_ZERO
        } else {
            rMagnitude
        }
        val r = remainder ?: freshInt(bounded(lowerR, upperR))
        val factor = if (constant != null) {
            DivMod(dividend, bigIntOf(constant), q, r, convention)
        } else {
            DivMod(dividend, divisor, q, r, convention)
        }
        relations[key] = factor
        return DivModRelation(q, r, listOf(factor))
    }
}
