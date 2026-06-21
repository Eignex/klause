package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.arithmetic.internals.ceilDivLong
import com.eignex.klause.solver.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.solver.factor.arithmetic.internals.floorDivLong
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState

/** CP propagator for [Product]: bounds propagation for `a * b = result`. */
internal class ProductPropagator(
    private val a: Int,
    private val b: Int,
    private val result: Int,
    val boolVars: IntArray,
    val intVars: IntArray,
) : Propagator {

    /**
     * Advisor subscription (#623): `propagate` derives everything from the corner products and
     * corner divisions of the `[min, max]` intervals of `a`, `b`, and `result` — it reads only
     * `min`/`max`. An interior hole can change none of those, so the propagator subscribes to
     * [IntEvent.LB_RAISED] / [IntEvent.UB_LOWERED] on each variable and skips interior
     * `VALUE_REMOVED` wakes. Deduplicated so an aliased operand (e.g. `a == b` for a square) is
     * subscribed once.
     */
    override val initialIntEventWatches: IntArray = IntEvent.boundEventWatches(intVars)

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, intVars, excludeIdx = -1, extraLit = 0)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val da = state.intDomains[a]
        val db = state.intDomains[b]
        val (pLo, pHi) = cornerProductRange(da, db)
        if (!tightenLong(state, result, pLo, pHi, state.composeIntVarAtomAntecedents(intArrayOf(a, b)))) return false

        val dr = state.intDomains[result]
        val dbAfter = state.intDomains[b]
        if (!reverseNarrow(state, target = a, divisorDomain = dbAfter, r = dr)) return false
        val daAfter = state.intDomains[a]
        if (!reverseNarrow(state, target = b, divisorDomain = daAfter, r = state.intDomains[result])) return false

        val drFinal = state.intDomains[result]
        if (0 !in drFinal.min..drFinal.max) {
            val antR = state.composeIntVarAtomAntecedents(intArrayOf(result))
            val daFinal = state.intDomains[a]
            if (daFinal.min == 0 && !state.tightenIntMin(a, 1, antR)) return false
            if (daFinal.max == 0 && !state.tightenIntMax(a, -1, antR)) return false
            val dbFinal = state.intDomains[b]
            if (dbFinal.min == 0 && !state.tightenIntMin(b, 1, antR)) return false
            if (dbFinal.max == 0 && !state.tightenIntMax(b, -1, antR)) return false
        }
        return true
    }

    private fun reverseNarrow(state: PropagationState, target: Int, divisorDomain: IntDomain, r: IntDomain): Boolean {
        if (0 in divisorDomain.min..divisorDomain.max) return true
        if (divisorDomain.min == divisorDomain.max) {
            return narrowByDivisor(state, target, divisorDomain.min.toLong(), r)
        }
        val rLo = r.min.toLong()
        val rHi = r.max.toLong()
        val dLo = divisorDomain.min.toLong()
        val dHi = divisorDomain.max.toLong()
        var tLo = Long.MAX_VALUE
        var tHi = Long.MIN_VALUE
        for (rn in longArrayOf(rLo, rHi)) {
            for (dn in longArrayOf(dLo, dHi)) {
                val c = ceilDivLong(rn, dn)
                val f = floorDivLong(rn, dn)
                if (c < tLo) tLo = c
                if (f > tHi) tHi = f
            }
        }
        if (tLo > tHi) return false
        val others = if (target == a) {
            intArrayOf(
                b,
                result,
            )
        } else if (target == b) {
            intArrayOf(a, result)
        } else {
            intArrayOf(a, b)
        }
        return tightenLong(state, target, tLo, tHi, state.composeIntVarAtomAntecedents(others))
    }

    private fun narrowByDivisor(state: PropagationState, target: Int, divisor: Long, r: IntDomain): Boolean {
        val rLo = r.min.toLong()
        val rHi = r.max.toLong()
        val lo: Long
        val hi: Long
        if (divisor > 0) {
            lo = ceilDivLong(rLo, divisor)
            hi = floorDivLong(rHi, divisor)
        } else {
            lo = ceilDivLong(rHi, divisor)
            hi = floorDivLong(rLo, divisor)
        }
        if (lo > hi) return false
        val others = if (target == a) {
            intArrayOf(
                b,
                result,
            )
        } else if (target == b) {
            intArrayOf(a, result)
        } else {
            intArrayOf(a, b)
        }
        return tightenLong(state, target, lo, hi, state.composeIntVarAtomAntecedents(others))
    }

    private fun cornerProductRange(da: IntDomain, db: IntDomain): Pair<Long, Long> {
        val p1 = da.min.toLong() * db.min
        val p2 = da.min.toLong() * db.max
        val p3 = da.max.toLong() * db.min
        val p4 = da.max.toLong() * db.max
        return minOf(p1, p2, p3, p4) to maxOf(p1, p2, p3, p4)
    }

    private fun tightenLong(state: PropagationState, v: Int, lo: Long, hi: Long, ant: IntArray? = null): Boolean {
        if (lo > Int.MAX_VALUE || hi < Int.MIN_VALUE) return false
        val loI = if (lo < Int.MIN_VALUE) Int.MIN_VALUE else lo.toInt()
        val hiI = if (hi > Int.MAX_VALUE) Int.MAX_VALUE else hi.toInt()
        if (!state.tightenIntMin(v, loI, ant)) return false
        if (!state.tightenIntMax(v, hiI, ant)) return false
        return true
    }
}
