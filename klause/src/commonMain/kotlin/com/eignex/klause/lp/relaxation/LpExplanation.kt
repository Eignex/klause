package com.eignex.klause.lp.relaxation

import com.eignex.klause.ir.Lit
import com.eignex.klause.lp.engine.CutExpression
import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.IntegerCertificate
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.exactBounds
import com.eignex.klause.lp.engine.exactShift
import com.eignex.klause.lp.engine.forEachRationalColumn
import com.eignex.klause.lp.engine.integerFarkasRay
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Int128
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.div
import com.eignex.klause.util.isZero
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.rem

/**
 * Turns LP certificates into learned-clause material over absolute variable-bound atoms, read off the
 * integer-multiplier [IntegerCertificate], which carries the reduced-cost signs
 * and dual-weight rows the reasons lean on. The artifacts share one
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
        // A continuous column's bounds are declared globals (or probe stand-ins), valid at every node.
        if (col < relaxation.colRealId.size && relaxation.colRealId[col] >= 0) return PREMISE_NONE
        val varId = relaxation.colVarId[col]
        if (varId < 0) return presencePremise(relaxation, session, col, lowerSide)
        val model = relaxation.model
        val side = if (lowerSide) model.exactBounds(col).lower else model.exactBounds(col).upper
        side ?: return PREMISE_AUX
        val value = model.exactShift(col) + side.number.value
        // An integer source has the same feasible values after directed rounding of a strict side.
        var threshold = value.num / value.den
        val remainder = !(value.num % value.den).isZero()
        if (lowerSide && value.signum() > 0 && remainder) threshold += BIG_ONE
        if (!lowerSide && value.signum() < 0 && remainder) threshold -= BIG_ONE
        if (side.strict && !remainder) {
            threshold = if (lowerSide) threshold + BIG_ONE else threshold - BIG_ONE
        }
        val endpoint = ExactLpNumber.of(BigFraction.of(threshold, BIG_ONE)).legacyLong() ?: return PREMISE_AUX
        if (relaxation.colIsBool[col]) {
            return when {
                lowerSide && endpoint == 1L && session.boolValue(varId) == true -> Lit.make(varId, false)

                // premise b (pinned true), negated
                !lowerSide && endpoint == 0L && session.boolValue(varId) == false -> Lit.make(varId, true)

                // premise ¬b (pinned false), negated
                (lowerSide && endpoint <= 0L) || (!lowerSide && endpoint >= 1L) -> PREMISE_NONE
                else -> PREMISE_AUX
            }
        }
        val domain = session.intDomain(varId)
        return if (lowerSide) {
            if (domain.min < endpoint) return PREMISE_AUX
            session.boundGeLit(varId, endpoint, positive = false)
        } else {
            if (domain.max > endpoint) return PREMISE_AUX
            session.boundLeLit(varId, endpoint, positive = false)
        }
    }

    fun boundPremiseLits(
        relaxation: LpRelaxation,
        session: PropagationSession,
        column: Int,
        lowerSide: Boolean,
    ): IntArray? = when (val literal = premiseLit(relaxation, session, column, lowerSide)) {
        PREMISE_AUX -> null
        PREMISE_NONE -> intArrayOf()
        else -> intArrayOf(literal)
    }

    private fun presencePremise(
        relaxation: LpRelaxation,
        session: PropagationSession,
        column: Int,
        lowerSide: Boolean,
    ): Int {
        val required = relaxation.colReq.getOrNull(column) ?: return PREMISE_AUX
        if (required.size % 2 != 0 || relaxation.colPresentUpper[column] < 0L) return PREMISE_AUX
        val model = relaxation.model
        if (!model.exactShift(column).isZero) return PREMISE_AUX
        val bounds = model.exactBounds(column)
        if (lowerSide) {
            return if (bounds.lower?.number?.value?.isZero == true && !bounds.lower.strict) PREMISE_NONE else PREMISE_AUX
        }
        val upper = bounds.upper ?: return PREMISE_AUX
        if (upper.strict) return PREMISE_AUX
        if (upper.number.value == BigFraction.ofLong(relaxation.colPresentUpper[column])) return PREMISE_NONE
        if (!upper.number.value.isZero) return PREMISE_AUX
        for (index in required.indices step 2) {
            val variable = required[index]
            if (variable !in 0L until session.problem.numIntVars.toLong()) return PREMISE_AUX
            val value = required[index + 1]
            if (!session.intDomain(variable.toInt()).contains(value)) {
                return session.equalityLit(variable.toInt(), value)
            }
        }
        return PREMISE_AUX
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
        cert: IntegerCertificate,
        session: PropagationSession,
    ): IntArray? {
        val lits = IntArrayList()
        val seen = IntHashSet()
        if (!addDualRowPremiseLits(lits, seen, relaxation, cert, session)) return null
        for (col in relaxation.colVarId.indices) {
            val sign = cert.reducedCostSign(col)
            if (sign == 0) continue
            val premises = boundPremiseLits(relaxation, session, col, lowerSide = sign > 0) ?: return null
            for (lit in premises) if (seen.add(lit)) lits.add(lit)
        }
        return lits.toIntArray()
    }

    /**
     * Nogood literals for an infeasibility [ray] (an exact integer Farkas certificate, [integerFarkasRay]),
     * or null when the certificate touches an auxiliary column, leans on a non-global row with no
     * recorded premises, or is constraint-only (no column premise — nothing to learn). The ray makes
     * `ρ·rhs > Σ_j max(0, ρ·A_j)·u_j`: each structural column with `ρ·A_j > 0` is seated at its upper
     * bound, each with `ρ·A_j < 0` at its lower bound, and those seated bounds — plus the recorded
     * premises of any ray-weighted non-global row — are jointly inconsistent with the (globally valid)
     * rows, so the clause `⋁ ¬(premise)` is implied by the problem alone. Every literal is false at the
     * dead node; the engine registers the clause where one can become unassigned (1UIP backjump / restart).
     */
    fun infeasibilityClause(relaxation: LpRelaxation, ray: LongArray, session: PropagationSession): IntArray? {
        val model = relaxation.model
        if (ray.size != model.m) return null
        val lits = IntArrayList()
        val seen = IntHashSet()
        val rows = (0 until model.m).filter { ray[it] != 0L }.toIntArray()
        if (!addRowPremiseLits(lits, seen, relaxation, rows, session)) return null
        for (col in relaxation.colVarId.indices) {
            val sign = rayColumnSign(model, ray, col) ?: return null
            if (sign == 0) continue
            // ρ·A_j > 0 ⇒ the column's upper bound is load-bearing (upper side); < 0 ⇒ lower side.
            val premises = boundPremiseLits(relaxation, session, col, lowerSide = sign < 0) ?: return null
            for (lit in premises) if (seen.add(lit)) lits.add(lit)
        }
        return lits.toIntArray()
    }

    private fun rayColumnSign(model: LpModel, ray: LongArray, column: Int): Int? {
        if (model.exactState != null || model.doubleView != null) {
            var dot = BigFraction.ZERO
            model.forEachRationalColumn(column) { row, value ->
                if (ray[row] != 0L) dot += BigFraction.ofLong(ray[row]) * value
            }
            return dot.signum()
        }
        val dot = Int128()
        model.forEachInColumn(column) { row, value -> dot.addProduct(ray[row], value) }
        if (dot.overflow) return null
        return if (dot.hi == 0L && dot.lo == 0L) 0 else if (dot.isNonNegative()) 1 else -1
    }

    /**
     * A theory lemma from an exact rational infeasibility over the load-bearing [rows]: the rows'
     * validity premises plus the current bound atoms of every integer-backed column those rows touch —
     * each a live assumption the refutation leans on, so their conjunction is provably real-infeasible
     * and the negated clause is globally valid. Null when some cited row's premise is inexpressible.
     */
    fun premiseClauseForRows(relaxation: LpRelaxation, rows: IntArray, session: PropagationSession): IntArray? {
        val lits = IntArrayList()
        val seen = IntHashSet()
        if (!addRowPremiseLits(lits, seen, relaxation, rows, session)) return null
        val model = relaxation.model
        val inRows = IntHashSet()
        for (r in rows) inRows.add(r)
        for (col in relaxation.colVarId.indices) {
            var touches = false
            model.forEachInColumnD(col) { i, _ -> if (inRows.contains(i)) touches = true }
            if (!touches) continue
            for (lower in listOf(false, true)) {
                val premises = boundPremiseLits(relaxation, session, col, lower) ?: return null
                for (literal in premises) if (seen.add(literal)) lits.add(literal)
            }
        }
        return lits.toIntArray()
    }

    /**
     * Append the negated validity premises of every non-global row in [rows]; false when some
     * non-global row has none recorded (the certificate is then inexpressible). The premise
     * thresholds were the live bounds at the relaxation's build, so each atom is true (and its
     * negation false) at the node — tightenings since the build only strengthen the atom.
     */
    fun addRowPremiseLits(
        lits: IntArrayList,
        seen: IntHashSet,
        relaxation: LpRelaxation,
        rows: IntArray,
        session: PropagationSession,
    ): Boolean {
        val model = relaxation.model
        for (r in rows) {
            if (r !in 0 until model.m || model.exactState?.rows?.row(r)?.active == false) return false
            if (model.rowGlobal[r]) continue
            val source = relaxation.sourceMap?.parent(r)
            if (source != null) {
                if (source.model !== session.problem || source.assumptions.isNotEmpty()) return false
                for (fact in source.facts) {
                    if (!fact.global && !addSourcePremise(lits, seen, fact.premise, session)) return false
                }
                continue
            }
            if (model.exactState != null) {
                val premises = model.exactState.model.row(r).premises ?: return false
                for (bound in premises.boundEntries()) {
                    val premise = CutPremise.Bound(
                        CutExpression(mapOf(CutSource(CutSourceKind.INTEGER, bound.variable) to BigFraction.ONE)),
                        bound.upper, bound.threshold.value,
                    )
                    if (!addSourcePremise(lits, seen, premise, session)) return false
                }
                for (literal in premises.literalEntries()) {
                    if (!addSourcePremise(lits, seen, CutPremise.Literal(literal), session)) return false
                }
                continue
            }
            val prem = model.rowPremises[r] ?: return false
            for (k in prem.vars.indices) {
                val lit = if (prem.isUpper[k]) {
                    session.boundLeLit(prem.vars[k], prem.thresholds[k], positive = false)
                } else {
                    session.boundGeLit(prem.vars[k], prem.thresholds[k], positive = false)
                }
                if (seen.add(lit)) lits.add(lit)
            }
            for (bl in prem.boolLits) {
                val neg = Lit.negate(bl)
                if (seen.add(neg)) lits.add(neg)
            }
        }
        return true
    }

    /** [addRowPremiseLits] over the rows carrying nonzero dual weight in an optimal [cert]. */
    fun addDualRowPremiseLits(
        lits: IntArrayList,
        seen: IntHashSet,
        relaxation: LpRelaxation,
        cert: IntegerCertificate,
        session: PropagationSession,
    ): Boolean {
        val rows = (0 until relaxation.model.m).filter { cert.dualNonzeroRow(it) }.toIntArray()
        return addRowPremiseLits(lits, seen, relaxation, rows, session)
    }

    private fun addSourcePremise(
        lits: IntArrayList,
        seen: IntHashSet,
        premise: CutPremise,
        session: PropagationSession,
    ): Boolean {
        if (premise is CutPremise.Fixed) {
            val expression = CutExpression(mapOf(premise.source to BigFraction.ONE))
            return addSourcePremise(lits, seen, CutPremise.Bound(expression, false, premise.value), session) &&
                addSourcePremise(lits, seen, CutPremise.Bound(expression, true, premise.value), session)
        }
        val literal = when (premise) {
            is CutPremise.Literal -> {
                if ((premise.literal ushr 1) !in 0 until session.problem.numBoolVars ||
                    session.boolValue(premise.literal ushr 1) != (premise.literal and 1 == 0)
                ) {
                    return false
                }
                premise.literal xor 1
            }

            is CutPremise.Excluded -> {
                if (premise.source.kind != CutSourceKind.INTEGER ||
                    premise.source.id !in 0 until session.problem.numIntVars
                ) {
                    return false
                }
                val value = ExactLpNumber.of(premise.value).legacyLong() ?: return false
                if (session.intDomain(premise.source.id).contains(value)) return false
                session.equalityLit(premise.source.id, value)
            }

            is CutPremise.Bound -> {
                val term = premise.expression.terms.entries.singleOrNull() ?: return false
                if (term.value.isZero || premise.strict) return false
                val threshold = (premise.value - premise.expression.constant) * term.value.reciprocal()
                val value = ExactLpNumber.of(threshold).legacyLong() ?: return false
                val upper = premise.upper == (term.value.signum() > 0)
                when (term.key.kind) {
                    CutSourceKind.INTEGER -> {
                        if (term.key.id !in 0 until session.problem.numIntVars) return false
                        val domain = session.intDomain(term.key.id)
                        if (upper) {
                            if (domain.max > value) return false
                            session.boundLeLit(term.key.id, value, positive = false)
                        } else {
                            if (domain.min < value) return false
                            session.boundGeLit(term.key.id, value, positive = false)
                        }
                    }

                    CutSourceKind.BOOLEAN -> {
                        if (term.key.id !in 0 until session.problem.numBoolVars) return false
                        if ((upper && value >= 1L) || (!upper && value <= 0L)) return true
                        if ((upper && value != 0L) || (!upper && value != 1L)) return false
                        if (session.boolValue(term.key.id) != !upper) return false
                        Lit.make(term.key.id, upper)
                    }

                    else -> return false
                }
            }

            else -> return false
        }
        if (seen.add(literal)) lits.add(literal)
        return true
    }
}
