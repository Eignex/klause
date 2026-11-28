package com.eignex.klause.lowering

import com.eignex.klause.ir.LinearOp
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.addExact
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.fitsLong
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.mulExact
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.times
import com.eignex.util.numbers.toLongExact
import com.eignex.util.numbers.unaryMinus

// A `Long` overflow promotes the fold to Wide; narrow folds avoid big-integer allocation.
internal sealed interface IntComb {
    class Narrow(val lin: LinComb) : IntComb
    class Wide(val lin: WideLinComb) : IntComb
}

internal data class WideLinComb(val coeffs: Map<Int, BigInt>, val constant: BigInt) {
    fun plus(other: WideLinComb): WideLinComb {
        val m = HashMap(coeffs)
        for ((v, c) in other.coeffs) m[v] = (m[v] ?: BIG_ZERO) + c
        return WideLinComb(m, constant + other.constant)
    }

    fun scaled(k: BigInt): WideLinComb = WideLinComb(coeffs.mapValues { it.value * k }, constant * k)
}

internal fun LinComb.toWide(): WideLinComb = WideLinComb(coeffs.mapValues { bigIntOf(it.value) }, bigIntOf(constant))

internal fun IntComb.toWide(): WideLinComb = when (this) {
    is IntComb.Narrow -> lin.toWide()
    is IntComb.Wide -> lin
}

internal fun IntComb.isConstant(): Boolean = when (this) {
    is IntComb.Narrow -> lin.coeffs.values.all { it == 0L }
    is IntComb.Wide -> lin.coeffs.values.all { it == BIG_ZERO }
}

// One mutable accumulator keeps work linear in operand count.
internal fun sumIntCombs(combs: List<IntComb>, negateTail: Boolean = false): IntComb {
    if (combs.all { it is IntComb.Narrow }) {
        runCatching {
            val m = HashMap<Int, Long>()
            var constant = 0L
            for (idx in combs.indices) {
                val lin = (combs[idx] as IntComb.Narrow).lin
                val s = if (negateTail && idx > 0) -1L else 1L
                for ((v, c) in lin.coeffs) m[v] = addExact(m[v] ?: 0L, mulExact(s, c))
                constant = addExact(constant, mulExact(s, lin.constant))
            }
            return IntComb.Narrow(LinComb(m, constant))
        }.getOrElse { if (it !is ArithmeticException) throw it }
    }
    val m = HashMap<Int, BigInt>()
    var constant = BIG_ZERO
    for (idx in combs.indices) {
        val w = combs[idx].toWide()
        val neg = negateTail && idx > 0
        for ((v, c) in w.coeffs) m[v] = (m[v] ?: BIG_ZERO) + (if (neg) -c else c)
        constant += if (neg) -w.constant else w.constant
    }
    return IntComb.Wide(WideLinComb(m, constant))
}

internal fun scaleIntComb(a: IntComb, k: Long): IntComb {
    if (a is IntComb.Narrow) {
        runCatching { return IntComb.Narrow(a.lin.scaled(k)) }.getOrElse { if (it !is ArithmeticException) throw it }
    }
    return IntComb.Wide(a.toWide().scaled(bigIntOf(k)))
}

internal fun scaleIntCombWide(a: IntComb, k: BigInt): IntComb = IntComb.Wide(a.toWide().scaled(k))

// A wide fold whose every value fits `Long` is returned as a LongRel.
internal sealed interface LinRelation {
    class LongRel(val vars: IntArray, val coeffs: LongArray, val bound: Long) : LinRelation
    class WideRel(val vars: IntArray, val coeffs: Array<BigInt>, val bound: BigInt) : LinRelation
}

internal fun intCombDiff(a: IntComb, b: IntComb, delta: Long): LinRelation {
    if (a is IntComb.Narrow && b is IntComb.Narrow) {
        runCatching {
            val (vars, coeffs, bound) = linCombDiff(a.lin, b.lin, delta)
            return LinRelation.LongRel(vars, coeffs, bound)
        }.getOrElse { if (it !is ArithmeticException) throw it }
    }
    val aw = a.toWide()
    val bw = b.toWide()
    val combined = HashMap(aw.coeffs)
    for ((v, c) in bw.coeffs) combined[v] = (combined[v] ?: BIG_ZERO) - c
    combined.entries.removeAll { it.value == BIG_ZERO }
    val bound = bw.constant - aw.constant + bigIntOf(delta)
    val vars = combined.keys.toIntArray()
    val coeffs = Array(vars.size) { combined.getValue(vars[it]) }
    if (bound.fitsLong() && coeffs.all { it.fitsLong() }) {
        return LinRelation.LongRel(vars, LongArray(vars.size) { coeffs[it].toLongExact() }, bound.toLongExact())
    }
    return LinRelation.WideRel(vars, coeffs, bound)
}

internal fun constProduct(consts: List<IntComb>): IntComb {
    if (consts.all { it is IntComb.Narrow }) {
        runCatching {
            var p = 1L
            for (c in consts) p = mulExact(p, (c as IntComb.Narrow).lin.constant)
            return IntComb.Narrow(LinComb(emptyMap(), p))
        }.getOrElse { if (it !is ArithmeticException) throw it }
    }
    var p = BIG_ONE
    for (c in consts) p *= c.toWide().constant
    return if (p.fitsLong()) {
        IntComb.Narrow(LinComb(emptyMap(), p.toLongExact()))
    } else {
        IntComb.Wide(WideLinComb(emptyMap(), p))
    }
}

internal fun scaleByConst(term: IntComb, k: IntComb): IntComb = when (k) {
    is IntComb.Narrow -> scaleIntComb(term, k.lin.constant)
    is IntComb.Wide -> scaleIntCombWide(term, k.lin.constant)
}

internal fun wideConstHolds(op: LinearOp, bound: BigInt): Boolean = when (op) {
    LinearOp.LE -> BIG_ZERO <= bound
    LinearOp.GE -> BIG_ZERO >= bound
    LinearOp.EQ -> bound == BIG_ZERO
    LinearOp.NE -> bound != BIG_ZERO
}
