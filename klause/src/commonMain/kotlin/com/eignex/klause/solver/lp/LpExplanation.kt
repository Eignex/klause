package com.eignex.klause.solver.lp

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.util.BigRational
import com.eignex.klause.util.IntArrayList

/**
 * Turns LP certificates into learned-clause material over absolute variable-bound atoms (#705: over
 * the sparse revised-simplex path's exact [ExactBasisCertifier.Certificate], which carries the same
 * reduced-cost signs and dual-weight rows the dense dual simplex exposed). Both artifacts share one
 * shape: a set of *premises* — column bounds the certificate leans on — whose negations form clause
 * literals, with the constraint rows kept implicit. Keeping the rows implicit is what makes the
 * clauses small, and it is sound exactly when every row the certificate leans on holds at every
 * solution of the problem ([LpModel.rowGlobal]). A non-global row with recorded validity premises
 * ([LpModel.rowPremises] — the live-big-M reified rows) is kept implicit by citing those bounds as
 * extra literals instead; a non-global row without premises (a locally separated or Gomory/MIR cut)
 * makes the certificate inexpressible and it is withheld rather than under-cited.
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
     * Reason atoms certifying the LP's objective lower bound for an OPTIMAL [cert] (the exact
     * basis-certificate), or null when the certificate touches an auxiliary column or a non-global
     * row carries dual weight. By LP duality `c·x = y·b + Σ_j d_j·x_j` holds row-wise, so for any
     * point satisfying the rows, `objective ≥ L` follows from `x_j ≥ lo_j` on the columns with
     * `d_j > 0` and `x_j ≤ ub_j` on those with `d_j < 0` — exactly the premises cited here. Basic
     * and zero-reduced-cost columns do not move the bound and stay uncited (a basic column's exact
     * reduced cost is `0`); rows stay implicit, which is why every row with `y_i ≠ 0` must be
     * globally valid or carry recorded validity premises ([LpModel.rowPremises]) cited alongside.
     */
    fun objectiveBoundReason(
        relaxation: LpRelaxation,
        cert: ExactBasisCertifier.Certificate,
        session: PropagationSession,
    ): IntArray? {
        val lits = IntArrayList()
        val seen = HashSet<Int>()
        if (!addDualRowPremiseLits(lits, seen, relaxation, cert, session)) return null
        for (col in relaxation.colVarId.indices) {
            val sign = cert.reducedCost[col].signum()
            if (sign == 0) continue
            when (val lit = premiseLit(relaxation, session, col, lowerSide = sign > 0)) {
                PREMISE_AUX -> return null
                PREMISE_NONE -> Unit
                else -> if (seen.add(lit)) lits.add(lit)
            }
        }
        return lits.toIntArray()
    }

    /**
     * Nogood literals for an infeasibility [ray] (an exact Farkas certificate, [ExactBasisCertifier.farkasRay]),
     * or null when the certificate touches an auxiliary column, leans on a non-global row with no
     * recorded premises, or is constraint-only (no column premise — nothing to learn). The ray makes
     * `ρ·rhs > Σ_j max(0, ρ·A_j)·u_j`: each structural column with `ρ·A_j > 0` is seated at its upper
     * bound, each with `ρ·A_j < 0` at its lower bound, and those seated bounds — plus the recorded
     * premises of any ray-weighted non-global row — are jointly inconsistent with the (globally valid)
     * rows, so the clause `⋁ ¬(premise)` is implied by the problem alone. Every literal is false at the
     * dead node; the engine registers the clause where one can become unassigned (1UIP backjump / restart).
     */
    fun infeasibilityClause(
        relaxation: LpRelaxation,
        ray: Array<BigRational>,
        session: PropagationSession,
    ): IntArray? {
        val model = relaxation.model
        val lits = IntArrayList()
        val seen = HashSet<Int>()
        val rows = (0 until model.m).filter { ray[it].signum() != 0 }.toIntArray()
        if (!addRowPremiseLits(lits, seen, relaxation, rows, session)) return null
        for (col in relaxation.colVarId.indices) {
            var aj = BigRational.ZERO
            model.forEachInColumn(col) { i, a -> aj += ray[i] * BigRational.of(a) }
            val sign = aj.signum()
            if (sign == 0) continue
            // ρ·A_j > 0 ⇒ the column's upper bound is load-bearing (upper side); < 0 ⇒ lower side.
            when (val lit = premiseLit(relaxation, session, col, lowerSide = sign < 0)) {
                PREMISE_AUX -> return null
                PREMISE_NONE -> Unit
                else -> if (seen.add(lit)) lits.add(lit)
            }
        }
        return if (lits.isEmpty()) null else lits.toIntArray()
    }

    /**
     * Append the negated validity premises of every non-global row in [rows]; false when some
     * non-global row has none recorded (the certificate is then inexpressible). The premise
     * thresholds were the live bounds at the relaxation's build, so each atom is true (and its
     * negation false) at the node — tightenings since the build only strengthen the atom.
     */
    fun addRowPremiseLits(
        lits: IntArrayList,
        seen: MutableSet<Int>,
        relaxation: LpRelaxation,
        rows: IntArray,
        session: PropagationSession,
    ): Boolean {
        val model = relaxation.model
        for (r in rows) {
            if (model.rowGlobal[r]) continue
            val prem = model.rowPremises[r] ?: return false
            for (k in prem.vars.indices) {
                val lit = if (prem.isUpper[k]) {
                    session.boundLeLit(prem.vars[k], prem.thresholds[k], positive = false)
                } else {
                    session.boundGeLit(prem.vars[k], prem.thresholds[k], positive = false)
                }
                if (seen.add(lit)) lits.add(lit)
            }
        }
        return true
    }

    /** [addRowPremiseLits] over the rows carrying nonzero dual weight in an optimal [cert]. */
    fun addDualRowPremiseLits(
        lits: IntArrayList,
        seen: MutableSet<Int>,
        relaxation: LpRelaxation,
        cert: ExactBasisCertifier.Certificate,
        session: PropagationSession,
    ): Boolean {
        val model = relaxation.model
        for (i in 0 until model.m) {
            if (!cert.dualNonzeroRow[i] || model.rowGlobal[i]) continue
            val prem = model.rowPremises[i] ?: return false
            for (k in prem.vars.indices) {
                val lit = if (prem.isUpper[k]) {
                    session.boundLeLit(prem.vars[k], prem.thresholds[k], positive = false)
                } else {
                    session.boundGeLit(prem.vars[k], prem.thresholds[k], positive = false)
                }
                if (seen.add(lit)) lits.add(lit)
            }
        }
        return true
    }
}
