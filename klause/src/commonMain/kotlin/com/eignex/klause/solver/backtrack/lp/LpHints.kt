package com.eignex.klause.solver.backtrack.lp

import com.eignex.klause.solver.backtrack.selector.VarRef
import com.eignex.klause.solver.lp.relaxation.LpRelaxation
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Per-call cache of the most recent fractional LP value of each variable, for LP-guided value
 * ordering. Filled by the node LP solve and read by the descent to order branch values toward
 * the LP value (round-toward-LP diving). Purely advisory — it reorders candidate values the value
 * heuristic already produced, so it can change search order but never the optimum or feasibility.
 *
 * `NaN` means "no current LP value" (the LP has not solved for this variable, or not at this node),
 * in which case ordering is left untouched.
 */
internal class LpHints(numIntVars: Int, numBoolVars: Int) {
    private val intVal = DoubleArray(numIntVars) { Double.NaN }
    private val boolVal = DoubleArray(numBoolVars) { Double.NaN }

    // Decayed running average of `|reduced cost|` each variable showed while nonbasic, a
    // reduced-cost pseudo-cost (an estimate of the objective's sensitivity to the variable). NaN = the
    // variable has never been nonbasic, so there is no estimate yet. Read by [branchScore]; advisory.
    private val intRc = DoubleArray(numIntVars) { Double.NaN }
    private val boolRc = DoubleArray(numBoolVars) { Double.NaN }

    /**
     * Record an LP solution: its fractional primal (per structural column,
     * `RevisedSimplex.FloatLpResult.primal`) and, from the [duals], the decayed reduced-cost average of
     * each variable (the reduced cost `dⱼ = cⱼ − yᵀAⱼ`; near-zero for basic columns). Keyed by the
     * relaxation's column→variable map. Purely advisory — drives value ([order]) and variable
     * ([branchScore]) selection, never feasibility or the optimum.
     */
    fun record(relaxation: LpRelaxation, primal: DoubleArray, duals: DoubleArray) {
        val model = relaxation.model
        for (col in relaxation.colVarId.indices) {
            val v = relaxation.colVarId[col]
            if (v < 0 || col >= primal.size) continue // auxiliary column (e.g. circuit arc) — no CP variable to hint
            if (relaxation.colIsBool[col]) boolVal[v] = primal[col] else intVal[v] = primal[col]
            // Reduced cost dⱼ = cⱼ − yᵀAⱼ over the structural column; |dⱼ| feeds the pseudo-cost average.
            if (col >= model.n) continue
            var dot = 0.0
            model.forEachInColumn(col) { i, a -> dot += duals[i] * a }
            val rc = abs(model.cost[col].toDouble() - dot)
            if (rc <= RC_TOL) continue // basic / zero-reduced-cost: no objective-sensitivity signal
            if (relaxation.colIsBool[col]) {
                boolRc[v] = if (boolRc[v].isNaN()) rc else RC_DECAY * boolRc[v] + (1.0 - RC_DECAY) * rc
            } else {
                intRc[v] = if (intRc[v].isNaN()) rc else RC_DECAY * intRc[v] + (1.0 - RC_DECAY) * rc
            }
        }
    }

    /**
     * Branching priority of [varRef] for reduced-cost-average variable selection: its reduced-cost
     * pseudo-cost weighted by how fractional its last LP value is (distance to the nearest integer).
     * A variable the LP already pins to an integer scores `0` (no branching needed); one with no LP
     * value scores `NaN` (the selector falls back). Higher = branch here first.
     */
    fun branchScore(varRef: VarRef): Double {
        val isBool = varRef is VarRef.Bool
        val id = when (varRef) {
            is VarRef.IntVar -> varRef.varId
            is VarRef.Bool -> varRef.varId
        }
        val vals = if (isBool) boolVal else intVal
        if (id < 0 || id >= vals.size) return Double.NaN
        val lpVal = vals[id]
        if (lpVal.isNaN()) return Double.NaN
        val frac = lpVal - floor(lpVal)
        val fractionality = min(frac, 1.0 - frac) // 0 when the LP value is integral
        val rc = (if (isBool) boolRc else intRc)[id]
        val weight = if (rc.isNaN()) 1.0 else rc + RC_EPS // unseen variables get the bare fractionality
        return weight * fractionality
    }

    /**
     * Reorder [values] so the integer nearest the variable's LP value comes first (then by increasing
     * distance), preserving the heuristic's order among ties. Returns [values] unchanged when there is
     * no LP value for [varRef].
     */
    fun order(varRef: VarRef, values: Sequence<Int>): Sequence<Int> {
        val hint = when (varRef) {
            is VarRef.IntVar -> intVal.getOrElse(varRef.varId) { Double.NaN }
            is VarRef.Bool -> boolVal.getOrElse(varRef.varId) { Double.NaN }
        }
        if (hint.isNaN()) return values
        val target = hint.roundToLong()
        // Stable sort by |value − round(LP)|: round-toward-LP diving, ties keep heuristic order.
        return values.sortedBy { abs(it.toLong() - target) }.asSequence()
    }

    private companion object {
        /** Below this, a reduced cost is treated as zero (basic column) — no pseudo-cost update. */
        const val RC_TOL = 1e-7

        /** Exponential-decay factor for the reduced-cost running average (older solves fade). */
        const val RC_DECAY = 0.9

        /** Floor added to the pseudo-cost weight so fractionality still ranks unseen-cost variables. */
        const val RC_EPS = 1e-6
    }
}
