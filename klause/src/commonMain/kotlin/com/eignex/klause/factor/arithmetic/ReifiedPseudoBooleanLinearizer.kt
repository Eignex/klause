package com.eignex.klause.factor.arithmetic

import com.eignex.klause.lp.Linearizer
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.lp.addExact
import com.eignex.klause.lp.subExact
import com.eignex.klause.model.PbOp

/**
 * Indicator rows for `auxBoolVar ↔ (Σ weights·literal ⟨op⟩ bound)` over Boolean literals — the
 * pseudo-Boolean analogue of [ReifiedLinearLinearizer]. The big-M comes from the declared `[0, 1]`
 * ranges (so the rows are global / CORE), and for `EQ` only the `aux = 1 ⇒ L = bound` direction is
 * emitted (its complement is a disjunction with no single LP cut).
 */
internal class ReifiedPseudoBooleanLinearizer(
    private val literals: IntArray,
    private val weights: IntArray,
    private val op: PbOp,
    private val bound: Int,
    private val auxBoolVar: Int,
) : Linearizer {
    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        val sum = BoolReifiedSum.fold(builder, literals, weights)
        val a = builder.boolColumn(auxBoolVar)
        val b = subExact(bound.toLong(), sum.constant)
        when (op) {
            PbOp.LE -> {
                val m1 = maxOf(0L, subExact(sum.lMax, b)) // aux=1 ⇒ L ≤ bound
                sum.reifiedRow(builder, a, m1, LinearOp.LE, addExact(b, m1))
                val m2 = maxOf(0L, subExact(addExact(b, 1L), sum.lMin)) // aux=0 ⇒ L ≥ bound+1
                sum.reifiedRow(builder, a, m2, LinearOp.GE, addExact(b, 1L))
            }

            PbOp.GE -> {
                val m1 = maxOf(0L, subExact(b, sum.lMin)) // aux=1 ⇒ L ≥ bound
                sum.reifiedRow(builder, a, -m1, LinearOp.GE, subExact(b, m1))
                val m2 = maxOf(0L, subExact(sum.lMax, subExact(b, 1L))) // aux=0 ⇒ L ≤ bound-1
                sum.reifiedRow(builder, a, -m2, LinearOp.LE, subExact(b, 1L))
            }

            PbOp.EQ -> {
                val mHi = maxOf(0L, subExact(sum.lMax, b)) // aux=1 ⇒ L ≤ bound
                sum.reifiedRow(builder, a, mHi, LinearOp.LE, addExact(b, mHi))
                val mLo = maxOf(0L, subExact(b, sum.lMin)) // aux=1 ⇒ L ≥ bound
                sum.reifiedRow(builder, a, -mLo, LinearOp.GE, subExact(b, mLo))
            }
        }
    }
}
