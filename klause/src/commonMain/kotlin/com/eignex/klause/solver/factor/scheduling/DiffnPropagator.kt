package com.eignex.klause.solver.factor.scheduling

import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState

/**
 * CP propagator for [Diffn]. Constructed by [Diffn.asPropagator] and holds pairwise
 * compulsory-parts / disjunctive propagation for the constant-size case, plus a
 * sound-only infeasibility check for the variable-size case.
 */
internal class DiffnPropagator(
    override val boolVars: IntArray,
    override val intVars: IntArray,
    private val xs: IntArray,
    private val ys: IntArray,
    private val widths: IntArray,
    private val heights: IntArray,
    private val widthVars: IntArray?,
    private val heightVars: IntArray?,
    private val nonStrict: Boolean,
    private val n: Int,
    private val varSize: Boolean,
) : Propagator {

    override val initialIntEventWatches: IntArray = IntEvent.boundEventWatches(intVars)

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, intVars, excludeIdx = -1, extraLit = 0)

    /**
     * Pairwise compulsory-parts / disjunctive propagation (constant-size only). When any
     * dimension is variable the size-dependent bound reasoning no longer holds, so we fall
     * back to the sound check: with the *minimum* possible sizes, if a pair must still overlap
     * on both axes the constraint is infeasible; otherwise no pruning. This keeps propagation
     * sound (never removes a feasible value) while LS does the heavy lifting on var-size diffn.
     */
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        if (varSize) return propagateVarSizeSoundOnly(state)
        for (i in 0 until n) {
            val wI = widths[i]
            val hI = heights[i]
            if (nonStrict && (wI == 0 || hI == 0)) continue
            val xiLo = state.intDomains[xs[i]].min
            val xiHi = state.intDomains[xs[i]].max
            val yiLo = state.intDomains[ys[i]].min
            val yiHi = state.intDomains[ys[i]].max
            for (j in i + 1 until n) {
                val wJ = widths[j]
                val hJ = heights[j]
                if (nonStrict && (wJ == 0 || hJ == 0)) continue
                val xjLo = state.intDomains[xs[j]].min
                val xjHi = state.intDomains[xs[j]].max
                val yjLo = state.intDomains[ys[j]].min
                val yjHi = state.intDomains[ys[j]].max
                val xMust = xiHi < xjLo + wJ && xjHi < xiLo + wI
                val yMust = yiHi < yjLo + hJ && yjHi < yiLo + hI
                if (xMust && yMust) return false
                if (xMust) {
                    val aFeasible = yiLo + hI <= yjHi
                    val bFeasible = yjLo + hJ <= yiHi
                    if (!aFeasible && !bFeasible) return false
                    if (aFeasible && !bFeasible) {
                        val ant = state.composeIntVarAtomAntecedents(intArrayOf(xs[i], xs[j], ys[i], ys[j]))
                        if (!state.tightenIntMax(ys[i], yjHi - hI, ant)) return false
                        if (!state.tightenIntMin(ys[j], yiLo + hI, ant)) return false
                    } else if (bFeasible && !aFeasible) {
                        val ant = state.composeIntVarAtomAntecedents(intArrayOf(xs[i], xs[j], ys[i], ys[j]))
                        if (!state.tightenIntMax(ys[j], yiHi - hJ, ant)) return false
                        if (!state.tightenIntMin(ys[i], yjLo + hJ, ant)) return false
                    }
                } else if (yMust) {
                    val aFeasible = xiLo + wI <= xjHi
                    val bFeasible = xjLo + wJ <= xiHi
                    if (!aFeasible && !bFeasible) return false
                    if (aFeasible && !bFeasible) {
                        val ant = state.composeIntVarAtomAntecedents(intArrayOf(xs[i], xs[j], ys[i], ys[j]))
                        if (!state.tightenIntMax(xs[i], xjHi - wI, ant)) return false
                        if (!state.tightenIntMin(xs[j], xiLo + wI, ant)) return false
                    } else if (bFeasible && !aFeasible) {
                        val ant = state.composeIntVarAtomAntecedents(intArrayOf(xs[i], xs[j], ys[i], ys[j]))
                        if (!state.tightenIntMax(xs[j], xiHi - wJ, ant)) return false
                        if (!state.tightenIntMin(xs[i], xjLo + wJ, ant)) return false
                    }
                }
            }
        }
        return true
    }

    /** Sound-only infeasibility check for the variable-size case: a pair is unconditionally
     *  infeasible iff it must overlap on both axes even at the *smallest* sizes each var allows. */
    private fun propagateVarSizeSoundOnly(state: PropagationState): Boolean {
        val wvars = widthVars
        val hvars = heightVars
        fun wMin(i: Int) = if (wvars == null) widths[i] else state.intDomains[wvars[i]].min
        fun hMin(i: Int) = if (hvars == null) heights[i] else state.intDomains[hvars[i]].min
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val wI = wMin(i)
                val hI = hMin(i)
                val wJ = wMin(j)
                val hJ = hMin(j)
                if (nonStrict && (wI == 0 || hI == 0 || wJ == 0 || hJ == 0)) continue
                val xMust = state.intDomains[xs[i]].max < state.intDomains[xs[j]].min + wJ &&
                    state.intDomains[xs[j]].max < state.intDomains[xs[i]].min + wI
                val yMust = state.intDomains[ys[i]].max < state.intDomains[ys[j]].min + hJ &&
                    state.intDomains[ys[j]].max < state.intDomains[ys[i]].min + hI
                if (xMust && yMust) return false
            }
        }
        return true
    }
}
