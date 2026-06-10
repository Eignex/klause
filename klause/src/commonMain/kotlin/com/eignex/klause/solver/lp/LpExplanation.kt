package com.eignex.klause.solver.lp

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.util.IntArrayList

/**
 * Turns an infeasible LP's Farkas certificate into a learned-clause nogood over absolute
 * variable-bound atoms. The certificate ([LpSolution.certCols] / [LpSolution.certBoundIsUpper]) names
 * the structural columns whose currently-seated bound is part of the dual ray proving the node LP
 * infeasible. Those bounds, together with the original constraints, admit no point — so the clause
 *
 *   `⋁ ¬(seated bound of each certificate column)`
 *
 * is implied by the original constraints alone (a missing column can only be dropped from the ray's
 * support when it does not participate, so the disjunction stays valid). A column seated at its lower
 * bound `lb` contributes `¬(x ≥ lb)`; at its upper bound `ub`, `¬(x ≤ ub)`; a Boolean column
 * contributes the negation of its pinned polarity. The clause is currently fully falsified — it is a
 * nogood for the dead node — so the engine must register it at a point where at least one literal can
 * become unassigned (e.g. a restart at root), exactly like the assignment nogoods.
 */
internal object LpExplanation {

    /**
     * Nogood literals for [solution]'s infeasibility certificate, or null when the solve was not
     * infeasible or carried no certificate (e.g. an all-slack ray over constraint rows only).
     */
    fun infeasibilityClause(relaxation: LpRelaxation, solution: LpSolution, session: PropagationSession): IntArray? {
        if (solution.status != LpStatus.INFEASIBLE || solution.certCols.isEmpty()) return null
        val model = relaxation.model
        val lits = IntArrayList(solution.certCols.size)
        for (k in solution.certCols.indices) {
            val col = solution.certCols[k]
            val varId = relaxation.colVarId[col]
            // An auxiliary column (e.g. a circuit arc) has no CP bound atom; dropping it would make
            // the clause too strong (unsound), so abandon learning when the certificate touches one.
            if (varId < 0) return null
            val atUpper = solution.certBoundIsUpper[k]
            if (relaxation.colIsBool[col]) {
                // Seated at upper ⇒ pinned true, at lower ⇒ pinned false; the clause negates it.
                lits.add(Lit.make(varId, !atUpper))
            } else if (atUpper) {
                val ub = model.loShift[col] + model.upper[col]
                lits.add(session.boundLeLit(varId, ub.toInt(), positive = false))
            } else {
                lits.add(session.boundGeLit(varId, model.loShift[col].toInt(), positive = false))
            }
        }
        return lits.toIntArray()
    }

    /**
     * Reason atoms certifying the LP's objective lower bound, for an OPTIMAL solve, or null
     * when the solve was not optimal or the certificate touches an auxiliary column. By LP duality the
     * optimum `L = c·x*` is held in place by the nonbasic structural variables seated at a bound with a
     * nonzero reduced cost: relaxing any of those bounds is the only way the objective could fall below
     * `L`; basic variables and zero-reduced-cost nonbasics do not move it. So those seated bounds, with
     * the (always-valid) constraints, imply `objective ≥ L`, and the returned literals are their
     * negations `¬(seated bound)` — exactly the antecedent shape [infeasibilityClause] produces, ready
     * to record as the reason for a propagated objective-variable bound. The constraints stay implicit
     * (globally valid), so they are not part of the reason.
     */
    fun objectiveBoundReason(relaxation: LpRelaxation, solution: LpSolution, session: PropagationSession): IntArray? {
        if (solution.status != LpStatus.OPTIMAL) return null
        val model = relaxation.model
        val status = solution.basis.status
        val lits = IntArrayList()
        for (col in relaxation.colVarId.indices) {
            if (status[col] == VarStatus.BASIC) continue
            if (solution.reducedCostNumerator[col] == 0L) continue // not load-bearing for the bound
            val varId = relaxation.colVarId[col]
            if (varId < 0) return null // auxiliary column — no CP bound atom to cite; abandon the reason
            val atUpper = status[col] == VarStatus.AT_UPPER
            if (relaxation.colIsBool[col]) {
                lits.add(Lit.make(varId, !atUpper))
            } else if (atUpper) {
                val ub = model.loShift[col] + model.upper[col]
                lits.add(session.boundLeLit(varId, ub.toInt(), positive = false))
            } else {
                lits.add(session.boundGeLit(varId, model.loShift[col].toInt(), positive = false))
            }
        }
        return lits.toIntArray()
    }
}
