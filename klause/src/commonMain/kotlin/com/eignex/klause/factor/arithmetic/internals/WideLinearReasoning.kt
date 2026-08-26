package com.eignex.klause.factor.arithmetic.internals

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.propagation.PropagationState
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * Exact arbitrary-precision reasoning for a linear row `Σ coeffs·vars ⟨op⟩ bound` whose coefficients or
 * bound exceed the 64-bit range. Shared by the bare [com.eignex.klause.factor.arithmetic.WideLinearPropagator]
 * and the reified [com.eignex.klause.factor.arithmetic.WideReifiedLinearPropagator], so the soundness-critical
 * interval and division logic lives in one place. Every operand is a [BigInteger], so there is no overflow to
 * guard against; only a derived variable bound is narrowed to `Long`, and only when it fits — a bound beyond
 * the `Long` range cannot constrain a `Long` domain, so skipping it is exact.
 */

private val LONG_MAX = BigInteger.fromLong(Long.MAX_VALUE)
private val LONG_MIN = BigInteger.fromLong(Long.MIN_VALUE)

/** `[sumLo, sumHi]`, the exact activity range of `Σ coeffs·vars` over the current domains. */
internal fun wideSumRange(
    state: PropagationState,
    vars: IntArray,
    coeffs: Array<BigInteger>,
): Pair<BigInteger, BigInteger> {
    var sumLo = BigInteger.ZERO
    var sumHi = BigInteger.ZERO
    for (i in vars.indices) {
        val d = state.intDomains[vars[i]]
        val c = coeffs[i]
        val a = c * BigInteger.fromLong(d.min)
        val b = c * BigInteger.fromLong(d.max)
        sumLo += if (a <= b) a else b
        sumHi += if (a <= b) b else a
    }
    return sumLo to sumHi
}

/** Whether the row is entailed by the current activity range (`[sumLo, sumHi]`). */
internal fun wideAlwaysHolds(op: LinearOp, sumLo: BigInteger, sumHi: BigInteger, bound: BigInteger): Boolean =
    when (op) {
        LinearOp.LE -> sumHi <= bound
        LinearOp.GE -> sumLo >= bound
        LinearOp.EQ -> sumLo == bound && sumHi == bound
        LinearOp.NE -> sumHi < bound || sumLo > bound
    }

/** Whether the row is refuted by the current activity range (`[sumLo, sumHi]`). */
internal fun wideNeverHolds(op: LinearOp, sumLo: BigInteger, sumHi: BigInteger, bound: BigInteger): Boolean =
    when (op) {
        LinearOp.LE -> sumLo > bound
        LinearOp.GE -> sumHi < bound
        LinearOp.EQ -> sumLo > bound || sumHi < bound
        LinearOp.NE -> sumLo == bound && sumHi == bound
    }

/**
 * Enforce `Σ coeffs·vars ⟨op⟩ bound` exactly: return `false` on a definite conflict, otherwise narrow each
 * variable's `Long` bound as far as the row implies (skipping a derived bound that escapes the `Long` range).
 * [auxLit] is the reifying literal to thread into every tighten's antecedent (`0` for a bare, unreified row).
 */
internal fun wideEnforceRow(
    state: PropagationState,
    vars: IntArray,
    coeffs: Array<BigInteger>,
    op: LinearOp,
    bound: BigInteger,
    auxLit: Int,
): Boolean {
    val n = vars.size
    val termLo = Array(n) { BigInteger.ZERO }
    val termHi = Array(n) { BigInteger.ZERO }
    var sumLo = BigInteger.ZERO
    var sumHi = BigInteger.ZERO
    for (i in 0 until n) {
        val d = state.intDomains[vars[i]]
        val c = coeffs[i]
        val a = c * BigInteger.fromLong(d.min)
        val b = c * BigInteger.fromLong(d.max)
        val lo = if (a <= b) a else b
        val hi = if (a <= b) b else a
        termLo[i] = lo
        termHi[i] = hi
        sumLo += lo
        sumHi += hi
    }
    if (wideNeverHolds(op, sumLo, sumHi, bound)) return false
    val rootFact = state.currentLevel == 0
    val includeAux = auxLit != 0
    fun ant(i: Int): IntArray? =
        if (rootFact && !includeAux) null else collectLinearTightenAntecedents(state, vars, i, auxLit, includeAux)
    if (op == LinearOp.NE) {
        for (i in 0 until n) {
            val c = coeffs[i]
            if (c == BigInteger.ZERO) continue
            val other = sumLo - termLo[i]
            if (other != sumHi - termHi[i]) continue // actionable only when every other term is pinned
            val rhs = bound - other
            val q = rhs / c
            if (q * c != rhs) continue // not an integer multiple — no value forbidden
            if (!q.fitsLong()) continue
            if (!state.excludeIntValue(vars[i], q.longValue(), ant(i))) return false
        }
        return true
    }
    for (i in 0 until n) {
        val c = coeffs[i]
        if (c == BigInteger.ZERO) continue
        val v = vars[i]
        val a = ant(i)
        if (op == LinearOp.LE || op == LinearOp.EQ) {
            val slack = bound - (sumLo - termLo[i]) // c·x ≤ bound − (Σ_lo without x)
            val ok = if (c > BigInteger.ZERO) {
                tightenMaxIfFits(state, v, floorDiv(slack, c), a)
            } else {
                tightenMinIfFits(state, v, ceilDiv(slack, c), a)
            }
            if (!ok) return false
        }
        if (op == LinearOp.GE || op == LinearOp.EQ) {
            val needed = bound - (sumHi - termHi[i]) // c·x ≥ bound − (Σ_hi without x)
            val ok = if (c > BigInteger.ZERO) {
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
private fun tightenMaxIfFits(state: PropagationState, v: Int, newMax: BigInteger, ant: IntArray?): Boolean {
    if (newMax >= LONG_MAX) return true
    return state.tightenIntMax(v, newMax.longValue(), ant)
}

private fun tightenMinIfFits(state: PropagationState, v: Int, newMin: BigInteger, ant: IntArray?): Boolean {
    if (newMin <= LONG_MIN) return true
    return state.tightenIntMin(v, newMin.longValue(), ant)
}

/** `⌊a / b⌋` (ionspin division truncates toward zero; adjust down when the exact quotient is negative
 *  with a nonzero remainder). */
internal fun floorDiv(a: BigInteger, b: BigInteger): BigInteger {
    val q = a / b
    val r = a - q * b
    return if (r != BigInteger.ZERO && (r < BigInteger.ZERO) != (b < BigInteger.ZERO)) q - BigInteger.ONE else q
}

/** `⌈a / b⌉` (adjust up when the exact quotient is positive with a nonzero remainder). */
internal fun ceilDiv(a: BigInteger, b: BigInteger): BigInteger {
    val q = a / b
    val r = a - q * b
    return if (r != BigInteger.ZERO && (r < BigInteger.ZERO) == (b < BigInteger.ZERO)) q + BigInteger.ONE else q
}

private fun BigInteger.fitsLong(): Boolean = this in LONG_MIN..LONG_MAX
