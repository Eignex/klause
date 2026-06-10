package com.eignex.klause.solver.lp

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.util.IntArrayList

/**
 * Turns LP certificates into learned-clause material over absolute variable-bound atoms. Both
 * artifacts here share one shape: a set of *premises* — column bounds the certificate leans on —
 * whose negations form clause literals, with the constraint rows kept implicit. Keeping the rows
 * implicit is what makes the clauses small, and it is sound exactly when every row the certificate
 * leans on holds at every solution of the problem ([LpModel.rowGlobal]); a certificate that leans
 * on a node-local row (a live-big-M reified row, a locally separated or Gomory/MIR cut) is
 * withheld rather than under-cited.
 *
 * A premise is cited from the column's **live LP bound**:
 *  - an integer column contributes `¬(x ≥ lo)` (lower side) or `¬(x ≤ ub)` (upper side) — a
 *    declared (unbranched) bound's negation is simply a constant-false literal, harmless;
 *  - a Boolean column carries information only when its column is collapsed: pinned **true**
 *    (`lo = 1`) contributes `¬b` on the lower side, pinned **false** (`ub = 0`) contributes `b` on
 *    the upper side; an unpinned column's `b ≥ 0` / `b ≤ 1` premise is vacuous and cited as
 *    nothing — its variable literal would be *unassigned* at the node, breaking the all-false
 *    contract. The side is what matters — *not* the seat name: a pinned column sits at a single
 *    point, so its recorded seat side is arbitrary, and mapping seat→polarity would emit the
 *    premise itself instead of its negation.
 *
 * Every emitted literal is false at the node that produced the certificate, which is the contract
 * both consumers require: `analyzeConflictClause` seeds 1UIP from an all-false clause, and
 * `implyInt*WithReason` records reasons whose literals are currently false.
 */
internal object LpExplanation {

    /** [premiseLit] result: the premise holds over the whole declared box — cite nothing. */
    const val PREMISE_NONE: Int = -1

    /** [premiseLit] result: the premise has no CP bound atom (auxiliary column) — abandon learning. */
    const val PREMISE_AUX: Int = -2

    /**
     * The negated premise literal for structural column [col], cited on its lower side
     * ([lowerSide], premise `x ≥ lo`) or upper side (premise `x ≤ ub`), where `lo`/`ub` are the
     * LP's live column bounds. Returns [PREMISE_NONE] for a vacuous premise and [PREMISE_AUX] when
     * the column has no backing CP variable. For an *optimal* certificate the side must follow the
     * reduced cost's sign (`d > 0` uses the lower bound, `d < 0` the upper — the seat name is
     * meaningless for a collapsed column); for a Farkas certificate it follows the recorded seat,
     * which is what the no-entering-column argument was evaluated against.
     */
    fun premiseLit(relaxation: LpRelaxation, session: PropagationSession, col: Int, lowerSide: Boolean): Int {
        val varId = relaxation.colVarId[col]
        if (varId < 0) return PREMISE_AUX
        val model = relaxation.model
        val lo = model.loShift[col]
        val hi = lo + model.upper[col]
        if (relaxation.colIsBool[col]) {
            return when {
                lowerSide && lo == 1L -> Lit.make(varId, false)

                // premise b (pinned true), negated
                !lowerSide && hi == 0L -> Lit.make(varId, true)

                // premise ¬b (pinned false), negated
                else -> PREMISE_NONE // b ≥ 0 / b ≤ 1: vacuous
            }
        }
        return if (lowerSide) {
            session.boundGeLit(varId, lo.toInt(), positive = false)
        } else {
            session.boundLeLit(varId, hi.toInt(), positive = false)
        }
    }

    /**
     * Nogood literals for [solution]'s infeasibility certificate, or null when the solve was not
     * infeasible, carried no certificate (an all-slack ray, or a ray through a non-global row —
     * see [DualSimplex]'s certificate gate), or the certificate touches an auxiliary column. The
     * certificate columns' seated bounds are jointly inconsistent with the (globally valid) rows,
     * so the clause `⋁ ¬(seated bound)` is implied by the problem alone. The clause is fully
     * falsified at the dead node; the engine registers it where a literal can become unassigned
     * (a 1UIP backjump, or a restart flush), exactly like the assignment nogoods.
     */
    fun infeasibilityClause(relaxation: LpRelaxation, solution: LpSolution, session: PropagationSession): IntArray? {
        if (solution.status != LpStatus.INFEASIBLE || solution.certCols.isEmpty()) return null
        val lits = IntArrayList(solution.certCols.size)
        for (k in solution.certCols.indices) {
            val col = solution.certCols[k]
            when (val lit = premiseLit(relaxation, session, col, lowerSide = !solution.certBoundIsUpper[k])) {
                PREMISE_AUX -> return null
                PREMISE_NONE -> Unit
                else -> lits.add(lit)
            }
        }
        return lits.toIntArray()
    }

    /**
     * Reason atoms certifying the LP's objective lower bound, for an OPTIMAL solve, or null when
     * the solve was not optimal, the certificate touches an auxiliary column, or a non-global row
     * carries dual weight. By LP duality `c·x = y·b + Σ_j d_j·x_j` holds row-wise, so for any point
     * satisfying the rows, `objective ≥ L` follows from `x_j ≥ lo_j` on the columns with `d_j > 0`
     * and `x_j ≤ ub_j` on those with `d_j < 0` — exactly the premises cited here. Basic and
     * zero-reduced-cost columns do not move the bound and stay uncited; rows stay implicit, which
     * is why every row with `y_i ≠ 0` must be globally valid.
     */
    fun objectiveBoundReason(relaxation: LpRelaxation, solution: LpSolution, session: PropagationSession): IntArray? {
        if (solution.status != LpStatus.OPTIMAL) return null
        val model = relaxation.model
        for (i in 0 until model.m) {
            if (solution.dualNumerator[i] != 0L && !model.rowGlobal[i]) return null
        }
        val status = solution.basis.status
        val lits = IntArrayList()
        for (col in relaxation.colVarId.indices) {
            if (status[col] == VarStatus.BASIC) continue
            val d = solution.reducedCostNumerator[col]
            if (d == 0L) continue
            when (val lit = premiseLit(relaxation, session, col, lowerSide = d > 0L)) {
                PREMISE_AUX -> return null
                PREMISE_NONE -> Unit
                else -> lits.add(lit)
            }
        }
        return lits.toIntArray()
    }
}
