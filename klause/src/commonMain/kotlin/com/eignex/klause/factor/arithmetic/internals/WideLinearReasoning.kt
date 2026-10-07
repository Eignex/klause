package com.eignex.klause.factor.arithmetic.internals

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.div
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact

/**
 * Exact arbitrary-precision reasoning for a linear row `Σ coeffs·vars ⟨op⟩ bound` whose coefficients or
 * bound exceed the 64-bit range. Shared by the bare [com.eignex.klause.factor.arithmetic.WideLinearPropagator]
 * and the reified [com.eignex.klause.factor.arithmetic.WideReifiedLinearPropagator], so the soundness-critical
 * interval and division logic lives in one place. Every operand is a [BigInt], so there is no overflow to
 * guard against; only a derived variable bound is narrowed to `Long`, and only when it fits — a bound beyond
 * the `Long` range cannot constrain a `Long` domain, so skipping it is exact.
 */

private val LONG_MAX = bigIntOf(Long.MAX_VALUE)
private val LONG_MIN = bigIntOf(Long.MIN_VALUE)

/** `[sumLo, sumHi]`, the exact activity range of `Σ coeffs·vars` over the current domains. */
internal fun wideSumRange(state: PropagationState, vars: IntArray, coeffs: Array<BigInt>): Pair<BigInt, BigInt> {
    var sumLo = BIG_ZERO
    var sumHi = BIG_ZERO
    for (i in vars.indices) {
        val d = state.intDomains[vars[i]]
        val c = coeffs[i]
        val a = c * bigIntOf(d.min)
        val b = c * bigIntOf(d.max)
        sumLo += if (a <= b) a else b
        sumHi += if (a <= b) b else a
    }
    return sumLo to sumHi
}

/** Whether the row is entailed by the current activity range (`[sumLo, sumHi]`). */
internal fun wideAlwaysHolds(op: LinearOp, sumLo: BigInt, sumHi: BigInt, bound: BigInt): Boolean = when (op) {
    LinearOp.LE -> sumHi <= bound
    LinearOp.GE -> sumLo >= bound
    LinearOp.EQ -> sumLo == bound && sumHi == bound
    LinearOp.NE -> sumHi < bound || sumLo > bound
}

/** Whether the row is refuted by the current activity range (`[sumLo, sumHi]`). */
internal fun wideNeverHolds(op: LinearOp, sumLo: BigInt, sumHi: BigInt, bound: BigInt): Boolean = when (op) {
    LinearOp.LE -> sumLo > bound
    LinearOp.GE -> sumHi < bound
    LinearOp.EQ -> sumLo > bound || sumHi < bound
    LinearOp.NE -> sumLo == bound && sumHi == bound
}

/**
 * Enforce `Σ coeffs·vars ⟨op⟩ bound` exactly: return `false` on a definite conflict, otherwise narrow each
 * variable's `Long` bound as far as the row implies (skipping a derived bound that escapes the `Long` range).
 * [auxLit] is the reifying literal to thread into every tighten's antecedent, null for a bare, unreified row
 * (every int is a literal, so 0 cannot mean none).
 */
internal fun wideEnforceRow(
    state: PropagationState,
    vars: IntArray,
    coeffs: Array<BigInt>,
    op: LinearOp,
    bound: BigInt,
    auxLit: Int?,
): Boolean {
    val n = vars.size
    val termLo = Array(n) { BIG_ZERO }
    val termHi = Array(n) { BIG_ZERO }
    var sumLo = BIG_ZERO
    var sumHi = BIG_ZERO
    for (i in 0 until n) {
        val d = state.intDomains[vars[i]]
        val c = coeffs[i]
        val a = c * bigIntOf(d.min)
        val b = c * bigIntOf(d.max)
        val lo = if (a <= b) a else b
        val hi = if (a <= b) b else a
        termLo[i] = lo
        termHi[i] = hi
        sumLo += lo
        sumHi += hi
    }
    if (wideNeverHolds(op, sumLo, sumHi, bound)) return false
    val rootFact = state.currentLevel == 0
    val includeAux = auxLit != null
    fun ant(i: Int): IntArray? =
        if (rootFact && !includeAux) null else collectLinearTightenAntecedents(state, vars, i, auxLit ?: 0, includeAux)
    if (op == LinearOp.NE) {
        for (i in 0 until n) {
            val c = coeffs[i]
            if (c == BIG_ZERO) continue
            val other = sumLo - termLo[i]
            if (other != sumHi - termHi[i]) continue // actionable only when every other term is pinned
            val rhs = bound - other
            val q = rhs / c
            if (q * c != rhs) continue // not an integer multiple — no value forbidden
            if (!q.fitsLong()) continue
            if (!state.excludeIntValue(vars[i], q.toLongExact(), ant(i))) return false
        }
        return true
    }
    for (i in 0 until n) {
        val c = coeffs[i]
        if (c == BIG_ZERO) continue
        val v = vars[i]
        val a = ant(i)
        if (op == LinearOp.LE || op == LinearOp.EQ) {
            val slack = bound - (sumLo - termLo[i]) // c·x ≤ bound − (Σ_lo without x)
            val ok = if (c > BIG_ZERO) {
                tightenMaxIfFits(state, v, floorDiv(slack, c), a)
            } else {
                tightenMinIfFits(state, v, ceilDiv(slack, c), a)
            }
            if (!ok) return false
        }
        if (op == LinearOp.GE || op == LinearOp.EQ) {
            val needed = bound - (sumHi - termHi[i]) // c·x ≥ bound − (Σ_hi without x)
            val ok = if (c > BIG_ZERO) {
                tightenMinIfFits(state, v, ceilDiv(needed, c), a)
            } else {
                tightenMaxIfFits(state, v, floorDiv(needed, c), a)
            }
            if (!ok) return false
        }
    }
    return true
}

// A derived max ≥ Long.MAX cannot bind a Long domain, so skipping it is exact; below that it fits a Long
// (a non-refuted row never derives a max below the variable's current min, so it stays ≥ Long.MIN).
private fun tightenMaxIfFits(state: PropagationState, v: Int, newMax: BigInt, ant: IntArray?): Boolean {
    if (newMax >= LONG_MAX) return true
    return state.tightenIntMax(v, newMax.toLongExact(), ant)
}

private fun tightenMinIfFits(state: PropagationState, v: Int, newMin: BigInt, ant: IntArray?): Boolean {
    if (newMin <= LONG_MIN) return true
    return state.tightenIntMin(v, newMin.toLongExact(), ant)
}

/** `⌊a / b⌋` (BigInt division truncates toward zero; adjust down when the exact quotient is negative
 *  with a nonzero remainder). */
internal fun floorDiv(a: BigInt, b: BigInt): BigInt {
    val q = a / b
    val r = a - q * b
    return if (r != BIG_ZERO && (r < BIG_ZERO) != (b < BIG_ZERO)) q - BIG_ONE else q
}

/** `⌈a / b⌉` (adjust up when the exact quotient is positive with a nonzero remainder). */
internal fun ceilDiv(a: BigInt, b: BigInt): BigInt {
    val q = a / b
    val r = a - q * b
    return if (r != BIG_ZERO && (r < BIG_ZERO) == (b < BIG_ZERO)) q + BIG_ONE else q
}
