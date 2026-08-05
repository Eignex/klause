package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * CP propagator for a [Linear] row whose coefficients or bound exceed the 64-bit range (the wide form).
 * All arithmetic is exact arbitrary precision ([BigInteger]), so there is no overflow to guard against and
 * no `unknown` degrade: the row is enforced exactly, including at a fully pinned leaf. Only the derived
 * variable *bound* is narrowed to a `Long` — and only when it fits, which it always does for any tightening
 * that actually constrains a `Long` domain (a derived bound beyond the `Long` range cannot bind a `Long`
 * variable, so skipping it loses nothing).
 *
 * The integer variables keep their ordinary `Long` domains and are branched normally; this propagator is
 * the only place the wide coefficients are read, and the wide value never reaches the domains, the trail,
 * or the LP relaxation (a wide row is excluded from the relaxation — see [Linear.linearize]).
 */
internal class WideLinearPropagator(
    val intVars: IntArray,
    private val vars: IntArray,
    private val coeffs: Array<BigInteger>,
    private val op: LinearOp,
    private val bound: BigInteger,
) : Propagator {

    /** Interval reasoning reads only `min`/`max` (see [LinearPropagator]); subscribe to bound moves. */
    override val initialIntEventWatches: IntArray = IntArray(vars.size * 2).also { out ->
        var w = 0
        for (v in vars) {
            out[w++] = IntEvent.pack(v, IntEvent.LB_RAISED)
            out[w++] = IntEvent.pack(v, IntEvent.UB_LOWERED)
        }
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
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
        // Exact feasibility: a breached sum extreme is a definite conflict (no 64-bit ambiguity).
        when (op) {
            LinearOp.LE -> if (sumLo > bound) return false
            LinearOp.GE -> if (sumHi < bound) return false
            LinearOp.EQ -> if (sumLo > bound || sumHi < bound) return false
            LinearOp.NE -> if (sumLo == bound && sumHi == bound) return false
        }
        val rootFact = state.currentLevel == 0
        if (op == LinearOp.NE) {
            // Only actionable once every other term is pinned: then this term is forced off one value.
            for (i in 0 until n) {
                val c = coeffs[i]
                val otherLo = sumLo - termLo[i]
                val otherHi = sumHi - termHi[i]
                if (otherLo != otherHi) continue
                val rhs = bound - otherLo
                val q = rhs / c
                if (q * c != rhs) continue // not an integer multiple — no value forbidden
                if (!q.fitsLong()) continue
                val ant = if (rootFact) null else collectLinearTightenAntecedents(state, vars, i, 0)
                if (!state.excludeIntValue(vars[i], q.longValue(), ant)) return false
            }
            return true
        }
        for (i in 0 until n) {
            val c = coeffs[i]
            val v = vars[i]
            val ant = if (rootFact) null else collectLinearTightenAntecedents(state, vars, i, 0)
            if (op == LinearOp.LE || op == LinearOp.EQ) {
                // Σ ≤ bound ⇒ c·x ≤ bound − (Σ_lo without x).
                val slack = bound - (sumLo - termLo[i])
                if (c > BigInteger.ZERO) {
                    if (!tightenMaxIfFits(state, v, floorDiv(slack, c), ant)) return false
                } else {
                    if (!tightenMinIfFits(state, v, ceilDiv(slack, c), ant)) return false
                }
            }
            if (op == LinearOp.GE || op == LinearOp.EQ) {
                // Σ ≥ bound ⇒ c·x ≥ bound − (Σ_hi without x).
                val needed = bound - (sumHi - termHi[i])
                if (c > BigInteger.ZERO) {
                    if (!tightenMinIfFits(state, v, ceilDiv(needed, c), ant)) return false
                } else {
                    if (!tightenMaxIfFits(state, v, floorDiv(needed, c), ant)) return false
                }
            }
        }
        return true
    }

    // A derived max ≥ Long.MAX cannot bind a Long domain, so skipping it is exact; below that it fits a
    // Long (a feasible row never derives a max below the variable's current min, so it is ≥ Long.MIN).
    private fun tightenMaxIfFits(state: PropagationState, v: Int, newMax: BigInteger, ant: IntArray?): Boolean {
        if (newMax >= LONG_MAX) return true
        return state.tightenIntMax(v, newMax.longValue(), ant)
    }

    private fun tightenMinIfFits(state: PropagationState, v: Int, newMin: BigInteger, ant: IntArray?): Boolean {
        if (newMin <= LONG_MIN) return true
        return state.tightenIntMin(v, newMin.longValue(), ant)
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, vars, excludeIdx = -1, extraLit = 0)

    private companion object {
        val LONG_MAX = BigInteger.fromLong(Long.MAX_VALUE)
        val LONG_MIN = BigInteger.fromLong(Long.MIN_VALUE)

        /** `⌊a / b⌋` (ionspin division truncates toward zero; adjust down when the exact quotient is
         *  negative with a nonzero remainder). */
        fun floorDiv(a: BigInteger, b: BigInteger): BigInteger {
            val q = a / b
            val r = a - q * b
            return if (r != BigInteger.ZERO && (r < BigInteger.ZERO) != (b < BigInteger.ZERO)) q - BigInteger.ONE else q
        }

        /** `⌈a / b⌉` (adjust up when the exact quotient is positive with a nonzero remainder). */
        fun ceilDiv(a: BigInteger, b: BigInteger): BigInteger {
            val q = a / b
            val r = a - q * b
            return if (r != BigInteger.ZERO && (r < BigInteger.ZERO) == (b < BigInteger.ZERO)) q + BigInteger.ONE else q
        }

        fun BigInteger.fitsLong(): Boolean = this in LONG_MIN..LONG_MAX
    }
}
