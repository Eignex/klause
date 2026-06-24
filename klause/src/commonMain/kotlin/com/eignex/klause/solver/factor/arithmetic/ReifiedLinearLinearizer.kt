package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.Linearizer
import com.eignex.klause.solver.RelaxationBuilder
import com.eignex.klause.solver.lp.addExact
import com.eignex.klause.solver.lp.mulExact
import com.eignex.klause.solver.lp.subExact

/**
 * Indicator rows for `auxBoolVar ↔ (L op bound)`, where `L = Σ coeffs·vars`. The big-Ms are the
 * tightest possible from the live range `[lMin, lMax]` of `L`, and the `¬(L op bound)` side uses
 * integrality (`¬(L ≤ bound) ⇔ L ≥ bound + 1`) so the rows are as strong as a single indicator allows.
 * For `EQ` only the `aux = 1 ⇒ L = bound` direction is emitted, and for `NE` only the `aux = 0 ⇒ L =
 * bound` direction (the complement is a disjunction with no single LP cut).
 *
 * A live big-M bakes branch-tightened bounds into a row's constants, so the row is marked global only
 * when its M equals the M the declared range would give; a non-global row carries the live bounds it
 * leaned on as premises (the engine derives them — see [RelaxationBuilder.bigMRow]).
 */
internal class ReifiedLinearLinearizer(
    private val auxBoolVar: Int,
    private val op: LinearOp,
    private val bound: Int,
    private val vars: IntArray,
    private val coeffs: IntArray,
) : Linearizer {
    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        var lMin = 0L
        var lMax = 0L
        var lMinD = 0L
        var lMaxD = 0L
        for (k in vars.indices) {
            val c = coeffs[k].toLong()
            val dom = builder.liveDomain(vars[k])
            val dec = builder.declaredDomain(vars[k])
            if (c >= 0L) {
                lMin = addExact(lMin, mulExact(c, dom.min.toLong()))
                lMax = addExact(lMax, mulExact(c, dom.max.toLong()))
                lMinD = addExact(lMinD, mulExact(c, dec.min.toLong()))
                lMaxD = addExact(lMaxD, mulExact(c, dec.max.toLong()))
            } else {
                lMin = addExact(lMin, mulExact(c, dom.max.toLong()))
                lMax = addExact(lMax, mulExact(c, dom.min.toLong()))
                lMinD = addExact(lMinD, mulExact(c, dec.max.toLong()))
                lMaxD = addExact(lMaxD, mulExact(c, dec.min.toLong()))
            }
        }
        val a = builder.boolColumn(auxBoolVar)
        val b = bound.toLong()
        val boundUp = addExact(b, 1L) // L ≥ bound + 1 is the integer negation of L ≤ bound
        val boundDown = subExact(b, 1L)

        // Emit `Σ coeffs·vars + auxCoeff·aux  op  rhs`, marked [global] when the live M matches the
        // declared-range M; non-global rows cite their [maxSide] live bounds as premises.
        fun emit(auxCoeff: Long, rowOp: LinearOp, rhs: Long, global: Boolean, maxSide: Boolean) {
            val cols = IntArray(vars.size + 1)
            val vals = LongArray(vars.size + 1)
            for (k in vars.indices) {
                cols[k] = builder.intColumn(vars[k])
                vals[k] = coeffs[k].toLong()
            }
            cols[vars.size] = a
            vals[vars.size] = auxCoeff
            builder.bigMRow(cols, vals, rowOp, rhs, global, maxSide)
        }

        when (op) {
            LinearOp.LE -> {
                val m1 = maxOf(0L, subExact(lMax, b)) // aux=1 ⇒ L ≤ bound
                emit(m1, LinearOp.LE, addExact(b, m1), m1 == maxOf(0L, subExact(lMaxD, b)), maxSide = true)
                val m2 = maxOf(0L, subExact(boundUp, lMin)) // aux=0 ⇒ L ≥ bound+1
                emit(m2, LinearOp.GE, boundUp, m2 == maxOf(0L, subExact(boundUp, lMinD)), maxSide = false)
            }

            LinearOp.GE -> {
                val m1 = maxOf(0L, subExact(b, lMin)) // aux=1 ⇒ L ≥ bound
                emit(-m1, LinearOp.GE, subExact(b, m1), m1 == maxOf(0L, subExact(b, lMinD)), maxSide = false)
                val m2 = maxOf(0L, subExact(lMax, boundDown)) // aux=0 ⇒ L ≤ bound-1
                emit(-m2, LinearOp.LE, boundDown, m2 == maxOf(0L, subExact(lMaxD, boundDown)), maxSide = true)
            }

            LinearOp.EQ -> {
                val mHi = maxOf(0L, subExact(lMax, b)) // aux=1 ⇒ L ≤ bound
                emit(mHi, LinearOp.LE, addExact(b, mHi), mHi == maxOf(0L, subExact(lMaxD, b)), maxSide = true)
                val mLo = maxOf(0L, subExact(b, lMin)) // aux=1 ⇒ L ≥ bound
                emit(-mLo, LinearOp.GE, subExact(b, mLo), mLo == maxOf(0L, subExact(b, lMinD)), maxSide = false)
            }

            LinearOp.NE -> {
                val mHi = maxOf(0L, subExact(lMax, b)) // aux=0 ⇒ L ≤ bound
                emit(-mHi, LinearOp.LE, b, mHi == maxOf(0L, subExact(lMaxD, b)), maxSide = true)
                val mLo = maxOf(0L, subExact(b, lMin)) // aux=0 ⇒ L ≥ bound
                emit(mLo, LinearOp.GE, b, mLo == maxOf(0L, subExact(b, lMinD)), maxSide = false)
            }
        }
    }
}
