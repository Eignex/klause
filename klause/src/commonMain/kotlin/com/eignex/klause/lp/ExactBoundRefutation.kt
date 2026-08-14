package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.solver.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * Whether interval propagation over the **genuinely open** ranges empties a domain, which refutes the
 * unbounded model outright.
 *
 * The LP refutation next door answers the same question, but it answers it in `Long` and `Double`: a
 * coefficient times an open bound is exactly the product that leaves 64 bits, and the build then throws
 * and the refutation is abandoned. That is the whole difficulty with an unbounded model — the values it
 * forces are large — so the cheap exact pass runs first. `e = 4096·a` with `a ≥ 2^60` and `e < 0` needs
 * no relaxation and no box to refute; it needs arithmetic that does not wrap.
 *
 * Only the refuting direction is usable: `false` means "no conclusion", never "satisfiable".
 *
 * Bounds only ever tighten, so the sweep is monotone; it stops at a fixpoint, at the round cap, or when
 * the deadline is spent. Stopping early only forfeits a refutation, never invents one.
 */
internal fun exactBoundsInfeasible(
    openBounds: Array<OpenIntBounds>,
    constraints: List<Linear>,
    cancellation: Cancellation = Cancellation.Never,
): Boolean {
    val n = openBounds.size
    if (n == 0 || constraints.isEmpty()) return false
    val lo = arrayOfNulls<BigInteger>(n)
    val hi = arrayOfNulls<BigInteger>(n)
    for (v in 0 until n) {
        lo[v] = openBounds[v].lo?.let { BigInteger.fromLong(it) }
        hi[v] = openBounds[v].hi?.let { BigInteger.fromLong(it) }
    }
    repeat(MAX_ROUNDS) {
        var changed = false
        for (f in constraints) {
            if (cancellation()) return false
            if (f.op != LinearOp.LE && f.op != LinearOp.EQ) continue
            if (f.vars.any { v -> v >= n }) continue
            if (tightenRow(f, lo, hi) { changed = true }) return true
        }
        if (!changed) return false
    }
    return false
}

/** One row's tightening pass; returns true as soon as a domain comes out empty. */
private inline fun tightenRow(
    f: Linear,
    lo: Array<BigInteger?>,
    hi: Array<BigInteger?>,
    onChange: () -> Unit,
): Boolean {
    val size = f.vars.size
    val coeff = Array(size) { k -> f.wideCoeffs?.get(k) ?: BigInteger.fromLong(f.coeff(k)) }
    val bound = f.wideBound ?: BigInteger.fromLong(f.bound)
    for (j in 0 until size) {
        val c = coeff[j]
        if (c.isZero()) continue
        var restMin: BigInteger? = BigInteger.ZERO
        var restMax: BigInteger? = BigInteger.ZERO
        for (i in 0 until size) {
            if (i == j) continue
            val ci = coeff[i]
            val positive = ci > BigInteger.ZERO
            val low = if (positive) lo[f.vars[i]] else hi[f.vars[i]]
            val high = if (positive) hi[f.vars[i]] else lo[f.vars[i]]
            restMin = if (restMin == null || low == null) null else restMin + ci * low
            restMax = if (restMax == null || high == null) null else restMax + ci * high
        }
        // Σ c·x ⟨op⟩ bound isolates term j as c·xⱼ ≤ bound − restMin, and an equality also gives
        // c·xⱼ ≥ bound − restMax.
        val upper = restMin?.let { rest -> bound - rest }
        val lower = if (f.op == LinearOp.EQ) restMax?.let { rest -> bound - rest } else null
        val positive = c > BigInteger.ZERO
        val newHi = if (positive) upper?.let { s -> floorDiv(s, c) } else lower?.let { s -> floorDiv(s, c) }
        val newLo = if (positive) lower?.let { s -> ceilDiv(s, c) } else upper?.let { s -> ceilDiv(s, c) }
        val v = f.vars[j]
        val curHi = hi[v]
        val curLo = lo[v]
        if (newHi != null && (curHi == null || newHi < curHi)) {
            hi[v] = newHi
            onChange()
        }
        if (newLo != null && (curLo == null || newLo > curLo)) {
            lo[v] = newLo
            onChange()
        }
        val l = lo[v]
        val h = hi[v]
        if (l != null && h != null && l > h) return true
    }
    return false
}

/** Enough sweeps for a long implication chain, few enough that a slowly-converging system still stops. */
private const val MAX_ROUNDS = 64

private fun floorDiv(a: BigInteger, b: BigInteger): BigInteger {
    val q = a / b
    return if (a % b != BigInteger.ZERO && (a.signum() < 0) != (b.signum() < 0)) q - BigInteger.ONE else q
}

private fun ceilDiv(a: BigInteger, b: BigInteger): BigInteger {
    val q = a / b
    return if (a % b != BigInteger.ZERO && (a.signum() < 0) == (b.signum() < 0)) q + BigInteger.ONE else q
}
