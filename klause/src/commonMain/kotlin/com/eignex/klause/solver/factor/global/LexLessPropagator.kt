package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState

/** CP propagation logic for `lex_less` / `lex_lesseq`. */
internal class LexLessPropagator(
    val boolVars: IntArray,
    val intVars: IntArray,
    private val xs: IntArray,
    private val ys: IntArray,
    private val strict: Boolean,
) : Propagator {

    /**
     * Advisor subscription (#623): lexicographic propagation is bound-only (see [propagate], which
     * reasons from `min`/`max` at the deciding position). An interior hole moves no bound, so the
     * factor subscribes to [IntEvent.LB_RAISED] / [IntEvent.UB_LOWERED] per variable and skips
     * interior `VALUE_REMOVED` wakes.
     */
    override val initialIntEventWatches: IntArray = run {
        val distinct = intVars.toHashSet()
        val out = IntArray(distinct.size * 2)
        var w = 0
        for (v in distinct) {
            out[w++] = IntEvent.pack(v, IntEvent.LB_RAISED)
            out[w++] = IntEvent.pack(v, IntEvent.UB_LOWERED)
        }
        out
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        val combined = IntArray(xs.size + ys.size).also {
            xs.copyInto(it, 0)
            ys.copyInto(it, xs.size)
        }
        return collectLinearTightenAntecedents(state, combined, excludeIdx = -1, extraLit = 0)
    }

    /**
     * Frisch–Hnich–Kiziltan–Miguel–Walsh lexicographic filtering ("Global Constraints for
     * Lexicographic Orderings"). Each call recomputes the two pointers from the current domains:
     *
     *  - `α` (`a`): the deciding index — the first position whose `(x, y)` pair is not yet pinned
     *    to a common value, so every earlier position is fixed-equal and the relation hinges here.
     *  - `β` (`b`): the most significant index from which the suffix is forced the wrong way
     *    (`x ≥ y`); `β = ∞` when the suffix can stay equal to the end without violating the
     *    length tie-break.
     *
     * The extra strength over a plain `xα ≤ yα` step is the `β` look-ahead: when `β = α + 1` the
     * suffix cannot rescue equality at `α`, so the position must be *strictly* ordered and the
     * bound tightens to `xα ≤ yα.max − 1` / `yα ≥ xα.min + 1`. `α ≥ β` means no lexicographic
     * completion survives — a contradiction.
     */
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val nx = xs.size
        val ny = ys.size
        val len = minOf(nx, ny)
        // When the whole common prefix is forced equal, the relation is settled by the length
        // tie-break: a proper-prefix `xs` is strictly less; equal length needs `!strict`; a longer
        // `xs` is strictly greater and so violates. `tailAllowsEquality` is also `β`'s `∞` test —
        // a suffix that may stay equal to the end forces no strict step at `α`.
        val tailAllowsEquality = nx < ny || (nx == ny && !strict)

        var a = 0
        while (true) {
            while (a < len) {
                val dx = state.intDomains[xs[a]]
                val dy = state.intDomains[ys[a]]
                if (dx.min == dx.max && dy.min == dy.max && dx.min == dy.min) a++ else break
            }
            if (a == len) return tailAllowsEquality

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
            if (b <= a) return false

            val strictHere = !betaInfinite && b == a + 1
            val newXMax = if (strictHere) dya.max - 1 else dya.max
            val newYMin = if (strictHere) dxa.min + 1 else dxa.min
            val ant = state.composeIntVarAtomAntecedents(reasonVars(a, i, strictHere))
            if (!state.tightenIntMax(xs[a], newXMax, ant)) return false
            if (!state.tightenIntMin(ys[a], newYMin, ant)) return false

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

    /**
     * Premise variables whose current bounds justify the tightening at `α`: the fixed-equal prefix
     * (why `α` is the deciding index), the `α` pair itself, and — when the step is strict — the
     * scanned suffix `α+1..scanStop` that forced strictness. A superset is sound; original-domain
     * bounds are level-0 facts and drop out inside [PropagationState.composeIntVarAtomAntecedents].
     */
    private fun reasonVars(a: Int, scanStop: Int, strictHere: Boolean): IntArray {
        val vars = ArrayList<Int>(2 * (a + 1))
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
