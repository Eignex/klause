package com.eignex.klause.lp.relaxation

import com.eignex.klause.ir.Lit
import com.eignex.klause.lp.engine.CutExpression
import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpDeductionCertificate
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpRowBound
import com.eignex.klause.lp.engine.exactBounds
import com.eignex.klause.lp.engine.exactShift
import com.eignex.klause.lp.engine.forEachRationalColumn
import com.eignex.klause.lp.engine.integerFarkasRay
import com.eignex.klause.propagation.PropagationSession
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.Int128
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.toLongExact
import com.eignex.util.numbers.unaryMinus

// Turns LP certificates into learned-clause material over absolute variable-bound atoms. Each artifact is
// a set of premises, the column bounds the certificate leans on, whose negations form the clause; rows stay
// implicit. That keeps clauses small and is sound only when every row the certificate leans on holds at
// every solution. A non-global row with recorded validity premises (the live-big-M reified rows) cites
// them as extra literals; a non-global row without premises (a locally separated or Gomory/MIR cut) makes
// the certificate inexpressible, and it is withheld rather than under-cited.
//
// Premises come from the column's live LP bound. A Boolean column carries information only when collapsed:
// pinned true cites ¬b on the lower side, pinned false cites b on the upper side, and an unpinned column
// cites nothing because its literal would be unassigned. The side decides polarity, never the recorded
// seat, which is arbitrary for a collapsed column.
//
// Every emitted literal is false at the node that produced the certificate: 1UIP analysis seeds from an
// all-false clause, and reasons require currently false literals.
internal object LpExplanation {
    private val minimumLong = bigIntOf(Long.MIN_VALUE)
    private val maximumLong = bigIntOf(Long.MAX_VALUE)

    // The premise holds over the whole declared box: cite nothing.
    const val PREMISE_NONE: Int = -1

    // The premise has no expressible CP atom, such as an auxiliary column: abandon.
    const val PREMISE_AUX: Int = -2

    // For an optimal certificate the side follows the reduced cost's sign (d > 0 lower, d < 0 upper); for a
    // Farkas ray it follows the sign of ρ·A_j.
    fun premiseLit(relaxation: LpRelaxation, session: PropagationSession, col: Int, lowerSide: Boolean): Int {
        if (col < relaxation.colRealId.size && relaxation.colRealId[col] >= 0) {
            val bounds = relaxation.model.exactBounds(col)
            val side = (if (lowerSide) bounds.lower else bounds.upper) ?: return PREMISE_AUX
            val declared = relaxation.model.exactState?.baseModel?.column(col)?.bounds
            if (declared != null && side != (if (lowerSide) declared.lower else declared.upper)) return PREMISE_AUX
            return PREMISE_NONE
        }
        val varId = relaxation.colVarId[col]
        if (varId < 0) return presencePremise(relaxation, session, col, lowerSide)
        val model = relaxation.model
        val side = if (lowerSide) model.exactBounds(col).lower else model.exactBounds(col).upper
        side ?: return PREMISE_AUX
        val value = model.exactShift(col) + side.number.value
        val kind = if (relaxation.colIsBool[col]) CutSourceKind.BOOLEAN else CutSourceKind.INTEGER
        return boundLiteral(CutSource(kind, varId), value, !lowerSide, side.strict, session)
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
            return if (bounds.lower?.number?.value?.isZero == true && !bounds.lower.strict) {
                PREMISE_NONE
            } else {
                PREMISE_AUX
            }
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

    // true is the lower side.
    fun citedSides(cert: LpDeductionCertificate, col: Int): List<Boolean> {
        val minimizing = when (cert.reducedCostSign(col)) {
            0 -> 0
            1 -> 1
            else -> 2
        }
        return CITED_SIDES[minimizing or cert.extraCitedSides(col)]
    }

    // By LP duality c·x = y·b + Σ_j d_j·x_j, so objective ≥ L follows from x_j ≥ lo_j where d_j > 0 and
    // x_j ≤ ub_j where d_j < 0, plus any extra sides the certificate names. Every row with y_i ≠ 0 must be
    // global or have its validity premises cited alongside.
    fun objectiveBoundReason(
        relaxation: LpRelaxation,
        cert: LpDeductionCertificate,
        session: PropagationSession,
        observer: LpCertificationObserver? = null,
    ): IntArray? {
        val lits = IntArrayList()
        val seen = IntHashSet()
        if (!addDualRowPremiseLits(lits, seen, relaxation, cert, session, observer)) return null
        for (col in relaxation.colVarId.indices) {
            for (lowerSide in citedSides(cert, col)) {
                val premises = boundPremiseLits(relaxation, session, col, lowerSide) ?: return null
                for (lit in premises) if (seen.add(lit)) lits.add(lit)
            }
        }
        return lits.toIntArray()
    }

    fun rowBoundReason(relaxation: LpRelaxation, cert: LpRowBound, session: PropagationSession): IntArray? {
        if (!cert.belongsTo(relaxation.model)) return null
        var integralSupport = true
        cert.forEachIntegralSupport { column ->
            val variable = relaxation.colVarId.getOrNull(column) ?: -1
            val count = if (relaxation.colIsBool.getOrNull(column) == true) session.problem.numBoolVars
            else session.problem.numIntVars
            if (variable !in 0 until count || relaxation.colRealId.getOrNull(column)?.let { it >= 0 } == true) {
                integralSupport = false
            }
        }
        if (!integralSupport) return null
        val lits = IntArrayList()
        val seen = IntHashSet()
        if (!addRowPremiseLits(lits, seen, relaxation, cert.premiseRows(), session)) return null
        var supported = true
        cert.forEachSupport { column, lower ->
            if (column >= relaxation.model.n) {
                val state = relaxation.model.exactState
                val bounds = state?.model?.column(column)?.bounds
                val base = state?.baseModel?.column(column)?.bounds
                val side = if (lower) bounds?.lower else bounds?.upper
                if (side == null || side.premises != null || side != if (lower) base?.lower else base?.upper) {
                    supported = false
                }
            } else {
                val premises = boundPremiseLits(relaxation, session, column, lower)
                if (premises == null) supported = false
                else for (literal in premises) if (seen.add(literal)) lits.add(literal)
            }
        }
        return if (supported) lits.toIntArray() else null
    }

    /**
     * Nogood for an exact integer Farkas [ray] ([integerFarkasRay]): columns with `ρ·A_j > 0` cite their
     * upper bound, those with `ρ·A_j < 0` their lower bound, plus the recorded premises of ray-weighted
     * non-global rows. Together they contradict the rows, so `⋁ ¬(premise)` follows from the problem alone.
     */
    fun infeasibilityClause(relaxation: LpRelaxation, ray: LongArray, session: PropagationSession): IntArray? {
        val model = relaxation.model
        if (ray.size != model.m) return null
        val lits = IntArrayList()
        val seen = IntHashSet()
        val rows = (0 until model.m).filter { ray[it] != 0L }.toIntArray()
        // A cited row's slack must rest on its unbranched source side, as for a dual certificate's rows.
        model.exactState?.let { state ->
            for (row in rows) {
                val support = state.activeSide(model.slackCol(row), ray[row] > 0L) ?: return null
                if (support.witness >= 0L || support.side.premises != null) return null
            }
        }
        if (!addRowPremiseLits(lits, seen, relaxation, rows, session)) return null
        for (col in relaxation.colVarId.indices) {
            val sign = rayColumnSign(model, ray, col) ?: return null
            if (sign == 0) continue
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

    // Theory lemma for an exact rational infeasibility over rows: their validity premises plus both bound
    // atoms of every column they touch are jointly real-infeasible, so the negated clause is globally valid.
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

    // False when a non-global row has no recorded premises. The thresholds were live bounds at build, so
    // each atom is true at the node; later tightenings only strengthen it.
    fun addRowPremiseLits(
        lits: IntArrayList,
        seen: IntHashSet,
        relaxation: LpRelaxation,
        rows: IntArray,
        session: PropagationSession,
    ): Boolean {
        val model = relaxation.model
        if (model.exactState == null && (model.rowGlobal.size != model.m || model.rowPremises.size != model.m)) {
            return false
        }
        for (r in rows) {
            if (r !in 0 until model.m || model.exactState?.rows?.row(r)?.active == false) return false
            if (model.exactState?.model?.row(r)?.global ?: model.rowGlobal[r]) continue
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
            if (prem.vars.size != prem.isUpper.size || prem.vars.size != prem.thresholds.size) return false
            for (k in prem.vars.indices) {
                if (prem.vars[k] !in 0 until session.problem.numIntVars) return false
                val premise = CutPremise.Bound(
                    CutExpression(mapOf(CutSource(CutSourceKind.INTEGER, prem.vars[k]) to BigFraction.ONE)),
                    prem.isUpper[k], BigFraction.ofLong(prem.thresholds[k]),
                )
                if (!addSourcePremise(lits, seen, premise, session)) return false
            }
            for (bl in prem.boolLits) {
                if (!addSourcePremise(lits, seen, CutPremise.Literal(bl), session)) return false
            }
        }
        return true
    }

    // Rows with dual support plus the rows proving an integral objective.
    fun addDualRowPremiseLits(
        lits: IntArrayList,
        seen: IntHashSet,
        relaxation: LpRelaxation,
        cert: LpDeductionCertificate,
        session: PropagationSession,
        observer: LpCertificationObserver? = null,
    ): Boolean {
        if (!cert.belongsTo(relaxation.model, observer)) return false
        val integralRows = cert.integralObjectiveRows()
        val state = relaxation.model.exactState
        val rows = (0 until relaxation.model.m).filter {
            if (state != null) {
                val column = relaxation.model.slackCol(it)
                for (lowerSide in citedSides(cert, column)) {
                    val support = state.activeSide(column, !lowerSide) ?: return false
                    if (support.witness >= 0L || support.side.premises != null) return false
                }
            }
            cert.dualNonzeroRow(it) || integralRows?.contains(it) == true
        }.toIntArray()
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
                val value = ExactLpNumber.of(premise.value).exactLong() ?: return false
                if (session.intDomain(premise.source.id).contains(value)) return false
                session.equalityLit(premise.source.id, value)
            }

            is CutPremise.Bound -> {
                val term = premise.expression.terms.entries.singleOrNull() ?: return false
                if (term.value.isZero) return false
                val threshold = (premise.value - premise.expression.constant) * term.value.reciprocal()
                val upper = premise.upper == (term.value.signum() > 0)
                when (val literal = boundLiteral(term.key, threshold, upper, premise.strict, session)) {
                    PREMISE_AUX -> return false
                    PREMISE_NONE -> return true
                    else -> literal
                }
            }

            else -> return false
        }
        if (seen.add(literal)) lits.add(literal)
        return true
    }

    private fun boundLiteral(
        source: CutSource,
        value: BigFraction,
        upper: Boolean,
        strict: Boolean,
        session: PropagationSession,
    ): Int = when (source.kind) {
        CutSourceKind.INTEGER -> integerBoundLiteral(source.id, integerEndpoint(value, upper, strict), upper, session)
        CutSourceKind.BOOLEAN -> booleanBoundLiteral(source.id, value, upper, strict, session)
        else -> PREMISE_AUX
    }

    private fun integerEndpoint(value: BigFraction, upper: Boolean, strict: Boolean): BigInt {
        // Integer inequalities use floor for <= and ceil for >=; strict sides use the neighboring lattice point.
        val rounded = when {
            value.den.compareTo(BIG_ONE) == 0 -> value.num
            upper == strict -> value.ceilInteger()
            else -> -value.negated().ceilInteger()
        }
        return if (!strict) rounded else if (upper) rounded - BIG_ONE else rounded + BIG_ONE
    }

    private fun integerBoundLiteral(
        variable: Int,
        threshold: BigInt,
        upper: Boolean,
        session: PropagationSession,
    ): Int {
        if (variable !in 0 until session.problem.numIntVars) return PREMISE_AUX
        if (threshold < minimumLong) return if (upper) PREMISE_AUX else PREMISE_NONE
        if (threshold > maximumLong) return if (upper) PREMISE_NONE else PREMISE_AUX
        val endpoint = threshold.toLongExact()
        val domain = session.intDomain(variable)
        return if (upper) {
            if (domain.max > endpoint) PREMISE_AUX else session.boundLeLit(variable, endpoint, positive = false)
        } else {
            if (domain.min < endpoint) PREMISE_AUX else session.boundGeLit(variable, endpoint, positive = false)
        }
    }

    private fun booleanBoundLiteral(
        variable: Int,
        value: BigFraction,
        upper: Boolean,
        strict: Boolean,
        session: PropagationSession,
    ): Int {
        if (variable !in 0 until session.problem.numBoolVars) return PREMISE_AUX
        fun holds(point: BigFraction): Boolean = if (upper) {
            point < value || (!strict && point == value)
        } else {
            point > value || (!strict && point == value)
        }
        val falseHolds = holds(BigFraction.ZERO)
        val trueHolds = holds(BigFraction.ONE)
        if (falseHolds && trueHolds) return PREMISE_NONE
        if (falseHolds == trueHolds || session.boolValue(variable) != trueHolds) return PREMISE_AUX
        return Lit.make(variable, !trueHolds)
    }
}

private val CITED_SIDES = listOf(emptyList(), listOf(true), listOf(false), listOf(true, false))
