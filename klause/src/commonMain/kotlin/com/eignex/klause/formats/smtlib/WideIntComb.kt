package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.formats.LinComb
import com.eignex.klause.formats.linCombDiff
import com.eignex.klause.formats.mulExact
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * A folded integer linear combination that is either 64-bit ([Narrow], the common fast path) or
 * arbitrary-precision ([Wide]). SMT-LIB integers are unbounded, so a fold stays [Narrow] until a
 * coefficient or constant overflows `Long`, then promotes to [Wide] — an over-Int64 literal lowers to a
 * wide factor instead of being rejected. The narrow arithmetic is unchanged (no per-op `BigInteger`
 * allocation), so only the rare wide fold pays for arbitrary precision.
 */
internal sealed interface IntComb {
    class Narrow(val lin: LinComb) : IntComb
    class Wide(val lin: WideLinComb) : IntComb
}

/** The `BigInteger` analogue of [LinComb]: `Σ coeffs·vars + constant` with arbitrary-precision values. */
internal data class WideLinComb(val coeffs: Map<Int, BigInteger>, val constant: BigInteger) {
    fun plus(other: WideLinComb): WideLinComb {
        val m = HashMap(coeffs)
        for ((v, c) in other.coeffs) m[v] = (m[v] ?: BigInteger.ZERO) + c
        return WideLinComb(m, constant + other.constant)
    }

    fun scaled(k: BigInteger): WideLinComb = WideLinComb(coeffs.mapValues { it.value * k }, constant * k)
}

internal fun LinComb.toWide(): WideLinComb =
    WideLinComb(coeffs.mapValues { BigInteger.fromLong(it.value) }, BigInteger.fromLong(constant))

internal fun IntComb.toWide(): WideLinComb = when (this) {
    is IntComb.Narrow -> lin.toWide()
    is IntComb.Wide -> lin
}

/** True when this combination has no variable terms (a bare constant). */
internal fun IntComb.isConstant(): Boolean = when (this) {
    is IntComb.Narrow -> lin.coeffs.isEmpty()
    is IntComb.Wide -> lin.coeffs.isEmpty()
}

/** Sum of two combinations, promoting to [IntComb.Wide] when a `Long` fold overflows. */
internal fun addIntComb(a: IntComb, b: IntComb): IntComb {
    if (a is IntComb.Narrow && b is IntComb.Narrow) {
        runCatching { return IntComb.Narrow(a.lin.plus(b.lin)) }.getOrElse { if (it !is ArithmeticException) throw it }
    }
    return IntComb.Wide(a.toWide().plus(b.toWide()))
}

/** This combination scaled by a `Long`, promoting to [IntComb.Wide] on overflow. */
internal fun scaleIntComb(a: IntComb, k: Long): IntComb {
    if (a is IntComb.Narrow) {
        runCatching { return IntComb.Narrow(a.lin.scaled(k)) }.getOrElse { if (it !is ArithmeticException) throw it }
    }
    return IntComb.Wide(a.toWide().scaled(BigInteger.fromLong(k)))
}

/** This combination scaled by an arbitrary-precision constant (always [IntComb.Wide]). */
internal fun scaleIntCombWide(a: IntComb, k: BigInteger): IntComb = IntComb.Wide(a.toWide().scaled(k))

/**
 * The relation `a ⟨op⟩ b` reduced to `(vars, coeffs, bound)` as `Σ coeffs·vars ⟨op⟩ bound`, either 64-bit
 * ([LongRel]) or arbitrary-precision ([WideRel]). A wide fold whose every value happens to fit `Long` is
 * returned as a [LongRel].
 */
internal sealed interface LinRelation {
    class LongRel(val vars: IntArray, val coeffs: LongArray, val bound: Long) : LinRelation
    class WideRel(val vars: IntArray, val coeffs: Array<BigInteger>, val bound: BigInteger) : LinRelation
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
    for ((v, c) in bw.coeffs) combined[v] = (combined[v] ?: BigInteger.ZERO) - c
    combined.entries.removeAll { it.value == BigInteger.ZERO }
    val bound = bw.constant - aw.constant + BigInteger.fromLong(delta)
    val vars = combined.keys.toIntArray()
    val coeffs = Array(vars.size) { combined.getValue(vars[it]) }
    // A wide fold whose values all fit Long stays a cheap Long row (only intermediate steps overflowed).
    if (bound.fitsLong() && coeffs.all { it.fitsLong() }) {
        return LinRelation.LongRel(vars, LongArray(vars.size) { coeffs[it].longValue() }, bound.longValue())
    }
    return LinRelation.WideRel(vars, coeffs, bound)
}

/** Product of the constant combinations in a `*` fold, staying `Long` until it overflows. */
internal fun constProduct(consts: List<IntComb>): IntComb {
    if (consts.all { it is IntComb.Narrow }) {
        runCatching {
            var p = 1L
            for (c in consts) p = mulExact(p, (c as IntComb.Narrow).lin.constant)
            return IntComb.Narrow(LinComb(emptyMap(), p))
        }.getOrElse { if (it !is ArithmeticException) throw it }
    }
    var p = BigInteger.ONE
    for (c in consts) p *= c.toWide().constant
    return if (p.fitsLong()) {
        IntComb.Narrow(LinComb(emptyMap(), p.longValue()))
    } else {
        IntComb.Wide(WideLinComb(emptyMap(), p))
    }
}

/** Scale [term] by the constant value of [k] (a variable-free combination), promoting as needed. */
internal fun scaleByConst(term: IntComb, k: IntComb): IntComb = when (k) {
    is IntComb.Narrow -> scaleIntComb(term, k.lin.constant)
    is IntComb.Wide -> scaleIntCombWide(term, k.lin.constant)
}

/** Whether the variable-free relation `0 ⟨op⟩ bound` holds (a wide row whose terms all cancelled). */
internal fun wideConstHolds(op: LinearOp, bound: BigInteger): Boolean = when (op) {
    LinearOp.LE -> BigInteger.ZERO <= bound
    LinearOp.GE -> BigInteger.ZERO >= bound
    LinearOp.EQ -> bound == BigInteger.ZERO
    LinearOp.NE -> bound != BigInteger.ZERO
}

/** The strict-inequality bound offset for a folded relation operator (`< / >` tighten by ∓1). */
internal fun strictDelta(op: String): Int = when (op) {
    "<" -> -1
    ">" -> 1
    else -> 0
}

/** The linear operator a folded relation lowers to (before the [strictDelta] bound offset). */
internal fun relLinearOp(op: String): LinearOp = when (op) {
    "<=", "<" -> LinearOp.LE
    ">=", ">" -> LinearOp.GE
    "=" -> LinearOp.EQ
    "distinct" -> LinearOp.NE
    else -> throw UnsupportedSmtException("relation '$op'")
}

private val LONG_MAX = BigInteger.fromLong(Long.MAX_VALUE)
private val LONG_MIN = BigInteger.fromLong(Long.MIN_VALUE)
private fun BigInteger.fitsLong(): Boolean = this in LONG_MIN..LONG_MAX
