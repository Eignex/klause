package com.eignex.klause.factor.global

import com.eignex.klause.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet

internal class LexLessPropagator(
    val boolVars: IntArray,
    val intVars: IntArray,
    private val xs: IntArray,
    private val ys: IntArray,
    private val strict: Boolean,
) : Propagator {

    // Propagation reads only bounds, so interior value removals need no wake.
    override val initialIntEventWatches: IntArray = run {
        val distinct = IntHashSet()
        for (v in intVars) distinct.add(v)
        val out = IntArray(distinct.size * 2)
        var w = 0
        distinct.forEach { v ->
            out[w++] = IntEvent.pack(v, IntEvent.LB_RAISED)
            out[w++] = IntEvent.pack(v, IntEvent.UB_LOWERED)
        }
        out
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        state.propagatorFailures[this]?.let { return state.composeIntVarAtomAntecedents(it) }
        val combined = IntArray(xs.size + ys.size).also {
            xs.copyInto(it, 0)
            ys.copyInto(it, xs.size)
        }
        return collectLinearTightenAntecedents(state, combined, excludeIdx = -1, extraLit = 0)
    }

    // Frisch, Hnich, Kiziltan, Miguel, Walsh, "Global Constraints for Lexicographic Orderings", CP 2002,
    // https://doi.org/10.1007/3-540-46135-3_7. `α` (`a`) is the first position not pinned to a common
    // value; `β` (`b`) the most significant index from which the suffix is forced `x ≥ y`, or `∞` when
    // the suffix may stay equal. `β = α + 1` forces a strict step at `α`; `α ≥ β` is a contradiction.
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        state.propagatorFailures.remove(this)
        val nx = xs.size
        val ny = ys.size
        val len = minOf(nx, ny)
        // A fully equal common prefix is settled by the length tie-break; this is also the `β = ∞` test.
        val tailAllowsEquality = nx < ny || (nx == ny && !strict)

        var a = 0
        while (true) {
            while (a < len) {
                val dx = state.intDomains[xs[a]]
                val dy = state.intDomains[ys[a]]
                if (dx.min == dx.max && dy.min == dy.max && dx.min == dy.min) a++ else break
            }
            if (a == len) {
                if (!tailAllowsEquality) {
                    state.propagatorFailures[this] = if (len == 0) {
                        IntArray(0)
                    } else {
                        reasonVars(len - 1, len - 1, strictHere = false)
                    }
                }
                return tailAllowsEquality
            }

            val dxa = state.intDomains[xs[a]]
            val dya = state.intDomains[ys[a]]
            if (dxa.max < dya.min) return true

            var i = a
            var b = -1
            while (i < len) {
                val dxi = state.intDomains[xs[i]]
                val dyi = state.intDomains[ys[i]]
                if (dxi.min > dyi.max) break
                if (dxi.min == dyi.max) {
                    if (b == -1) b = i
                } else {
                    b = -1
                }
                i++
            }
            val betaInfinite: Boolean
            if (i == len && tailAllowsEquality) {
                betaInfinite = true
                b = Int.MAX_VALUE
            } else {
                betaInfinite = false
                if (b == -1) b = i
            }
            if (b <= a) {
                state.propagatorFailures[this] = reasonVars(a, i, strictHere = true)
                return false
            }

            val strictHere = !betaInfinite && b == a + 1
            val newXMax = if (strictHere) dya.max - 1 else dya.max
            val newYMin = if (strictHere) dxa.min + 1 else dxa.min
            val premises = reasonVars(a, i, strictHere)
            val ant = state.composeIntVarAtomAntecedents(premises)
            state.propagatorFailures[this] = premises
            if (!state.tightenIntMax(xs[a], newXMax, ant)) return false
            if (!state.tightenIntMin(ys[a], newYMin, ant)) return false
            state.propagatorFailures.remove(this)

            val dxa2 = state.intDomains[xs[a]]
            val dya2 = state.intDomains[ys[a]]
            if (dxa2.max < dya2.min) return true
            if (dxa2.min == dxa2.max && dya2.min == dya2.max && dxa2.min == dya2.min) {
                a++
                continue
            }
            return true
        }
    }

    // The fixed-equal prefix, the `α` pair, and for a strict step the suffix `α+1..scanStop`. A superset
    // is sound; root-domain bounds drop out as level-0 facts.
    private fun reasonVars(a: Int, scanStop: Int, strictHere: Boolean): IntArray {
        val vars = IntArrayList(2 * (a + 1))
        for (j in 0 until a) {
            vars.add(xs[j])
            vars.add(ys[j])
        }
        vars.add(xs[a])
        vars.add(ys[a])
        if (strictHere) {
            val len = minOf(xs.size, ys.size)
            for (j in a + 1..minOf(scanStop, len - 1)) {
                vars.add(xs[j])
                vars.add(ys[j])
            }
        }
        return vars.toIntArray()
    }
}
