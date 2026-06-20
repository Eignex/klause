package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.solver.propagation.PropagationState

/** CP propagation logic for `sort`. */
internal interface SortPropagator : Propagator {
    val xs: IntArray
    val ys: IntArray

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, intVars, excludeIdx = -1, extraLit = 0)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        var allSingleton = true
        for (v in intVars) {
            if (state.intDomains[v].min != state.intDomains[v].max) {
                allSingleton = false
                break
            }
        }
        if (allSingleton) {
            val xv = IntArray(xs.size) { state.intDomains[xs[it]].min }.also { it.sort() }
            for (i in ys.indices) {
                if (state.intDomains[ys[i]].min != xv[i]) return false
            }
        }
        val antYs = state.composeIntVarAtomAntecedents(ys)
        val antXs = state.composeIntVarAtomAntecedents(xs)
        for (i in 0 until ys.size - 1) {
            val lo = state.intDomains[ys[i]].min
            if (!state.tightenIntMin(ys[i + 1], lo, antYs)) return false
        }
        for (i in ys.size - 2 downTo 0) {
            val hi = state.intDomains[ys[i + 1]].max
            if (!state.tightenIntMax(ys[i], hi, antYs)) return false
        }
        var xsMinOfMins = Int.MAX_VALUE
        var xsMinOfMaxes = Int.MAX_VALUE
        var xsMaxOfMins = Int.MIN_VALUE
        var xsMaxOfMaxes = Int.MIN_VALUE
        for (x in xs) {
            val d = state.intDomains[x]
            if (d.min < xsMinOfMins) xsMinOfMins = d.min
            if (d.max < xsMinOfMaxes) xsMinOfMaxes = d.max
            if (d.min > xsMaxOfMins) xsMaxOfMins = d.min
            if (d.max > xsMaxOfMaxes) xsMaxOfMaxes = d.max
        }
        if (!state.tightenIntMin(ys[0], xsMinOfMins, antXs)) return false
        if (!state.tightenIntMax(ys[0], xsMinOfMaxes, antXs)) return false
        val nv = ys.size
        if (!state.tightenIntMin(ys[nv - 1], xsMaxOfMins, antXs)) return false
        if (!state.tightenIntMax(ys[nv - 1], xsMaxOfMaxes, antXs)) return false
        val yLo = state.intDomains[ys[0]].min
        val yHi = state.intDomains[ys[nv - 1]].max
        for (x in xs) {
            if (!state.tightenIntMin(x, yLo, antYs)) return false
            if (!state.tightenIntMax(x, yHi, antYs)) return false
        }
        return true
    }
}
