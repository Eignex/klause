package com.eignex.klause.factor.arithmetic

import com.eignex.klause.lp.Linearizer
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.lp.addExact
import com.eignex.klause.lp.subExact

/**
 * Indicator rows for `auxBoolVar ↔ (min ≤ #true literals ≤ max)`. Only the `aux = 1 ⇒ (count ≥ min ∧
 * count ≤ max)` direction yields LP cuts (the `aux = 0` side is the disjunction `count < min ∨ count >
 * max`, whose hull is the whole interval), so two CORE rows are emitted with declared `[0, 1]` big-Ms.
 */
internal class ReifiedCardinalityLinearizer(
    private val literals: IntArray,
    private val min: Int,
    private val max: Int,
    private val auxBoolVar: Int,
) : Linearizer {
    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        val sum = BoolReifiedSum.fold(builder, literals, weights = null)
        val a = builder.boolColumn(auxBoolVar)
        val lo = subExact(min.toLong(), sum.constant)
        val hi = subExact(max.toLong(), sum.constant)
        val mHi = maxOf(0L, subExact(sum.lMax, hi)) // aux=1 ⇒ count ≤ max
        sum.reifiedRow(builder, a, mHi, LinearOp.LE, addExact(hi, mHi))
        val mLo = maxOf(0L, subExact(lo, sum.lMin)) // aux=1 ⇒ count ≥ min
        sum.reifiedRow(builder, a, -mLo, LinearOp.GE, subExact(lo, mLo))
    }
}
