package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.lp.LpRelaxation
import com.eignex.klause.solver.lp.LpSolution
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Per-call cache of the most recent fractional LP value of each variable, for LP-guided value
 * ordering (#246). Filled by the node LP solve and read by the descent to order branch values toward
 * the LP value (round-toward-LP diving). Purely advisory — it reorders candidate values the value
 * heuristic already produced, so it can change search order but never the optimum or feasibility.
 *
 * `NaN` means "no current LP value" (the LP has not solved for this variable, or not at this node),
 * in which case ordering is left untouched.
 */
internal class LpHints(numIntVars: Int, numBoolVars: Int) {
    private val intVal = DoubleArray(numIntVars) { Double.NaN }
    private val boolVal = DoubleArray(numBoolVars) { Double.NaN }

    /** Record an LP solution's fractional primal, keyed by the relaxation's column→variable map. */
    fun record(relaxation: LpRelaxation, solution: LpSolution) {
        for (col in relaxation.colVarId.indices) {
            val v = relaxation.colVarId[col]
            val value = solution.primal(col)
            if (relaxation.colIsBool[col]) boolVal[v] = value else intVal[v] = value
        }
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
}
