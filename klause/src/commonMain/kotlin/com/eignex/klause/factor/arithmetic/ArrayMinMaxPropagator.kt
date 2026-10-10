package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.util.IntArrayList

/** CP propagator for [ArrayMinMax]: bounds propagation for `result = max/min(xs)`. */
internal class ArrayMinMaxPropagator(
    private val result: Int,
    private val xs: IntArray,
    private val max: Boolean,
    val boolVars: IntArray,
    val intVars: IntArray,
) : Propagator {

    /**
     * Advisor subscription: `propagate` tightens `result` against the operands' bounds and
     * pushes `result`'s bound back onto every operand — reading only `min`/`max`. An interior hole
     * never moves a `min`/`max`, so the factor subscribes to [IntEvent.LB_RAISED] /
     * [IntEvent.UB_LOWERED] on each variable and skips interior `VALUE_REMOVED` wakes. A repeated
     * operand is subscribed once.
     */
    override val initialIntEventWatches: IntArray = IntEvent.boundEventWatches(intVars)

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        state.propagatorFailures[this] ?: collectLinearTightenAntecedents(state, intVars, excludeIdx = -1, extraLit = 0)

    // Each step cites only the side it reads: the result's bound against every term's bound on the same side,
    // and one term's opposite bound for the result's other bound. A step that fails also cites the bound it
    // crossed.
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        state.propagatorFailures.remove(this)
        if (max) {
            var hiBound = Long.MIN_VALUE
            var loBound = Long.MIN_VALUE
            var loVar = xs[0]
            for (i in xs) {
                val d = state.intDomains[i]
                if (d.max > hiBound) hiBound = d.max
                if (d.min > loBound) {
                    loBound = d.min
                    loVar = i
                }
            }
            val termsUpper = bounds(state, xs, upper = true)
            if (!state.tightenIntMax(result, hiBound, termsUpper)) return fail(state, termsUpper, result, upper = false)
            val termLower = bounds(state, intArrayOf(loVar), upper = false)
            if (!state.tightenIntMin(result, loBound, termLower)) return fail(state, termLower, result, upper = true)
            val rMax = state.intDomains[result].max
            val resultUpper = bounds(state, intArrayOf(result), upper = true)
            for (i in xs) {
                if (!state.tightenIntMax(i, rMax, resultUpper)) return fail(state, resultUpper, i, upper = false)
            }
        } else {
            var loBound = Long.MAX_VALUE
            var hiBound = Long.MAX_VALUE
            var hiVar = xs[0]
            for (i in xs) {
                val d = state.intDomains[i]
                if (d.min < loBound) loBound = d.min
                if (d.max < hiBound) {
                    hiBound = d.max
                    hiVar = i
                }
            }
            val termsLower = bounds(state, xs, upper = false)
            if (!state.tightenIntMin(result, loBound, termsLower)) return fail(state, termsLower, result, upper = true)
            val termUpper = bounds(state, intArrayOf(hiVar), upper = true)
            if (!state.tightenIntMax(result, hiBound, termUpper)) return fail(state, termUpper, result, upper = false)
            val rMin = state.intDomains[result].min
            val resultLower = bounds(state, intArrayOf(result), upper = false)
            for (i in xs) {
                if (!state.tightenIntMin(i, rMin, resultLower)) return fail(state, resultLower, i, upper = true)
            }
        }
        return true
    }

    private fun fail(state: PropagationState, premises: IntArray?, crossed: Int, upper: Boolean): Boolean {
        val extra = bounds(state, intArrayOf(crossed), upper)
        state.propagatorFailures[this] = when {
            premises == null -> extra
            extra == null -> premises
            else -> premises + extra
        }
        return false
    }

    // The [upper] (else lower) bound atom of each of [vars] that sits inside its root domain.
    private fun bounds(state: PropagationState, vars: IntArray, upper: Boolean): IntArray? {
        val out = IntArrayList()
        for (v in vars) {
            val d = state.intDomains[v]
            val root = state.rootDomains[v]
            if (upper && d.max < root.max) out.add(Lit.make(state.atomVarLe(v, d.max), false))
            if (!upper && d.min > root.min) out.add(Lit.make(state.atomVarGe(v, d.min), false))
        }
        return if (out.size == 0) null else out.toIntArray()
    }
}
