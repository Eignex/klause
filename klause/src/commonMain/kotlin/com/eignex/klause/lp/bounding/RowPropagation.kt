package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpBoundAssertion
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.RegisteredTheoryDecision
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.util.Cancellation
import com.eignex.klause.simplex.exact.BigFraction
import com.ionspin.kotlin.bignum.integer.BigInteger
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchExplanation

internal interface RowPropagation {
    fun propagate(lp: LpPropagator, context: SearchContext): RowPropagationResult
    fun isPublished(decision: SearchDecision, context: SearchContext): Boolean = false
    fun prepareAssertion(lp: LpPropagator, decision: SearchDecision, context: SearchContext): Boolean = false
}

internal sealed interface RowPropagationResult {
    data object Skipped : RowPropagationResult
    data object Published : RowPropagationResult
    data object Indeterminate : RowPropagationResult
    data class Conflict(val explanation: SearchExplanation) : RowPropagationResult
}

internal data class RowImplication(
    val column: Int,
    val upper: Boolean,
    val side: ExactLpSide,
    val premise: SearchAtomPremise,
)

internal class ExactRowPropagation(limits: RowPropagationLimits = RowPropagationLimits()) : RowPropagation {
    val budget = RowPropagationBudget(limits)
    private val reasons = RowPropagationReasons(budget)
    private var owner: LpPropagator? = null
    private var seen: LpExactState? = null
    private var seenEpoch: Any? = null
    private data class NativeReservation(
        val state: LpExactState,
        val epoch: Any,
        val context: SearchContext,
        val decision: SearchDecision,
        val cost: Pair<Long, Long>,
        val bits: Int,
    )
    private var reservation: NativeReservation? = null
    private var nativeBits = 0
    var passes = 0L
        private set
    var published = 0L
        private set
    var mappingDeclines = 0L
        private set
    var reasonDeclines = 0L
        private set
    var rowSkips = 0L
        private set
    var implications = 0L
        private set

    override fun isPublished(decision: SearchDecision, context: SearchContext): Boolean = reasons.known(decision, context)

    override fun prepareAssertion(lp: LpPropagator, decision: SearchDecision, context: SearchContext): Boolean {
        val reserved = reservation ?: return false
        reservation = null
        if (owner !== lp || reserved.context !== context || reserved.decision != decision ||
            !lp.rowPublicationCurrent(reserved.state, context, reserved.epoch)
        ) return false
        val cost = lp.rowNativeCost(reserved.state, reserved.bits) ?: return false
        return cost.first <= reserved.cost.first && cost.second <= reserved.cost.second
    }

    override fun propagate(lp: LpPropagator, context: SearchContext): RowPropagationResult {
        val state = lp.state ?: return RowPropagationResult.Skipped
        val epoch = lp.rowReasonEpoch
        if ((owner != null && owner !== lp) || !lp.rowPublicationCurrent(state, context, epoch)) {
            return RowPropagationResult.Indeterminate
        }
        owner = lp
        if ((seen === state && seenEpoch === epoch) || budget.declined || state.model.m > budget.limits.rows ||
            state.model.numVars > budget.limits.columns
        ) return RowPropagationResult.Skipped
        if (!context.consumeCheck()) return RowPropagationResult.Indeterminate
        passes++
        seen = state
        seenEpoch = epoch
        budget.cancellation = Cancellation { !lp.rowPublicationCurrent(state, context, epoch) }
        val pass = scan(state) { lp.rowBoundPremise(state, it) } ?: return stopped(context)
        pass.conflict?.let { premise ->
            val expanded = reasons.expand(premise, context, epoch)
            val explanation = expanded?.let { reasons.explanation(it, context) }
            if (explanation != null && lp.rowPublicationCurrent(state, context, epoch)) {
                return RowPropagationResult.Conflict(explanation)
            }
            reasonDeclines++
        }
        if (pass.implications.isNotEmpty() && !admitNativeModel(state)) {
            mappingDeclines += pass.implications.size
            return stopped(context)
        }
        for (candidate in pass.implications) {
            if (!budget.input(candidate.side.number.value) || !budget.charge(2048, 2048)) break
            val candidateBits = maxOf(nativeBits, candidate.side.number.value.num.bitLength(),
                candidate.side.number.value.den.bitLength())
            val nativeCost = lp.rowNativeCost(state, candidateBits)
            if (nativeCost == null) {
                mappingDeclines++
                continue
            }
            val decision = lp.rowDecision(state, candidate.column, candidate.upper, candidate.side, context)
            if (!lp.rowPublicationCurrent(state, context, epoch)) return RowPropagationResult.Indeterminate
            if (decision !is SearchDecision.Theory ||
                decision.decision !is RegisteredTheoryDecision
            ) {
                mappingDeclines++
                continue
            }
            val literal = context.atomLiteral(decision)
            if (literal == null) {
                mappingDeclines++
                continue
            }
            if (context.boolValue(literal ushr 1) == (literal and 1 == 0)) continue
            val expanded = reasons.expand(candidate.premise, context, epoch)
            val explanation = expanded?.let { reasons.explanation(it, context, decision) }
            if (explanation == null || !reasons.reserve(decision, expanded, context, epoch)) {
                reasonDeclines++
                continue
            }
            if (!budget.charge(nativeCost.first, nativeCost.second)) break
            reservation = NativeReservation(state, epoch, context, decision, nativeCost, candidateBits)
            if (!lp.rowPublicationCurrent(state, context, epoch)) return RowPropagationResult.Indeterminate
            val result = context.imply(literal, explanation)
            if (result is ComponentResult.Conflict) {
                return RowPropagationResult.Conflict(explanation)
            }
            if (!lp.rowPublicationCurrent(state, context, epoch) ||
                result !is ComponentResult.Consistent
            ) return RowPropagationResult.Indeterminate
            published++
            return RowPropagationResult.Published
        }
        return stopped(context)
    }

    private fun admitNativeModel(state: LpExactState): Boolean {
        val model = state.baseModel
        nativeBits = 0
        fun admit(value: BigFraction): Boolean {
            nativeBits = maxOf(nativeBits, value.num.bitLength(), value.den.bitLength())
            return budget.input(value)
        }
        if (!budget.charge(model.n.toLong() * model.m + model.numVars, model.n.toLong() * model.m)) return false
        for (column in 0 until model.numVars) {
            val value = model.column(column)
            if (!admit(value.origin.value) || !admit(model.objective.cost(column).value)) return false
            value.bounds.lower?.let { if ((it.premises?.size ?: 0L) > 128 || !admit(it.number.value)) return false }
            value.bounds.upper?.let { if ((it.premises?.size ?: 0L) > 128 || !admit(it.number.value)) return false }
            if (column < model.n && model.entries(column).any { !admit(it.number.value) }) return false
        }
        for (row in 0 until model.m) {
            if ((model.row(row).premises?.size ?: 0L) > 128 || !admit(model.rhs(row).value)) return false
        }
        return admit(model.objective.constant.value) && admit(model.objective.scale.value) &&
            admit(model.objective.externalConstant.value)
    }

    private fun stopped(context: SearchContext): RowPropagationResult = if (context.cancelled() || budget.cancellation()) {
        RowPropagationResult.Indeterminate
    } else {
        RowPropagationResult.Skipped
    }

    internal data class Bound(val side: ExactLpSide, val premise: SearchAtomPremise, val derived: Boolean = false)
    internal data class Pass(
        val implications: List<RowImplication>,
        val conflict: SearchAtomPremise?,
        val lower: List<Bound?>,
        val upper: List<Bound?>,
    )
    private data class Term(val column: Int, val coefficient: BigFraction)
    private data class Row(val terms: List<Term>, val rhs: BigFraction, val premise: SearchAtomPremise)

    internal fun scan(state: LpExactState, source: (LpBoundAssertion) -> SearchAtomPremise): Pass? {
        val model = state.model
        if (model.m > budget.limits.rows || model.numVars > budget.limits.columns ||
            !budget.charge(model.n.toLong() * model.m + 1, model.n.toLong() * model.m * 4 + model.numVars * 8L)
        ) return null
        val rows = Array(model.m) { ArrayList<Term>() }
        for (column in 0 until model.n) {
            for (entry in model.entries(column)) {
                if (!budget.charge()) return null
                if (!entry.number.value.isZero) rows[entry.row].add(Term(column, entry.number.value))
            }
        }
        val equations = ArrayList<Row>()
        for (row in rows.indices) {
            if (!budget.charge(allocation = 4)) return null
            if (!state.rows.row(row).active) continue
            rows[row].add(Term(model.n + row, BigFraction.ONE))
            if (rows[row].size > budget.limits.rowLength || !budget.input(model.rhs(row).value) ||
                rows[row].any { !budget.input(it.coefficient) }
            ) {
                rowSkips++
                continue
            }
            equations.add(Row(rows[row], model.rhs(row).value, rowPremise(state, row)))
        }
        fun bound(column: Int, upper: Boolean): Bound? {
            val assertion = state.activeSide(column, upper) ?: return null
            val premise = ArrayList<SearchAtomPremise>()
            premise.add(source(assertion))
            assertion.side.premises?.let { premise.add(reasons.metadata(it) ?: SearchAtomPremise.Unavailable) }
            if (column >= model.n) premise.add(rowPremise(state, column - model.n))
            return Bound(assertion.side, SearchAtomPremise.All(premise))
        }
        val lower = MutableList(model.numVars) { bound(it, false) }
        val upper = MutableList(model.numVars) { bound(it, true) }
        for (column in lower.indices) {
            if (!budget.input(model.column(column).origin.value)) return null
            lower[column]?.let { if (!budget.input(it.side.number.value)) return null }
            upper[column]?.let { if (!budget.input(it.side.number.value)) return null }
        }
        var changed: Boolean
        do {
            changed = false
            for (row in equations) {
                for (direction in 0..1) {
                    val minimum = direction == 0
                    if (!budget.charge(row.terms.size.toLong())) return finish(lower, upper)
                    var missing = -1
                    var missingCount = 0
                    for (term in row.terms) {
                        if (selected(term, minimum, lower, upper) == null) {
                            missing = term.column
                            missingCount++
                        }
                    }
                    if (missingCount > 1) continue
                    for (target in row.terms) {
                        if (missingCount == 1 && target.column != missing) continue
                        val candidate = derive(row, target, minimum, lower, upper) ?: continue
                        val isUpper = minimum == (target.coefficient.signum() > 0)
                        var side = candidate.side
                        val column = model.column(target.column)
                        if (column.integral && column.origin.value.den == BigInteger.ONE) {
                            val rounded = budget.round(side.number.value, isUpper, side.strict) ?: continue
                            side = ExactLpSide(ExactLpNumber.of(rounded))
                        }
                        val sides = if (isUpper) upper else lower
                        val previous = sides[target.column]
                        val comparison = previous?.let {
                            budget.compare(side.number.value, it.side.number.value) ?: return finish(lower, upper)
                        }
                        if (comparison != null && !(if (isUpper) comparison < 0 else comparison > 0) &&
                            !(comparison == 0 && side.strict && !previous.side.strict)
                        ) continue
                        if (!budget.charge(allocation = 4)) return finish(lower, upper)
                        sides[target.column] = candidate.copy(side = side)
                        implications++
                        changed = true
                        val lo = lower[target.column]
                        val hi = upper[target.column]
                        if (lo != null && hi != null) {
                            val crossing = budget.compare(lo.side.number.value, hi.side.number.value)
                                ?: return finish(lower, upper)
                            if (crossing > 0 || (crossing == 0 && (lo.side.strict || hi.side.strict))) {
                                return finish(lower, upper, SearchAtomPremise.All(listOf(lo.premise, hi.premise)))
                            }
                        }
                    }
                }
            }
        } while (changed && !budget.declined)
        return finish(lower, upper)
    }

    private fun rowPremise(state: LpExactState, row: Int): SearchAtomPremise =
        if (state.model.row(row).global && state.rows.row(row).depth == null) {
            SearchAtomPremise.All(emptyList())
        } else {
            reasons.metadata(state.model.row(row).premises) ?: SearchAtomPremise.Unavailable
        }

    private fun selected(term: Term, minimum: Boolean, lower: List<Bound?>, upper: List<Bound?>): Bound? =
        if (minimum == (term.coefficient.signum() > 0)) lower[term.column] else upper[term.column]

    private fun derive(
        row: Row,
        target: Term,
        minimum: Boolean,
        lower: List<Bound?>,
        upper: List<Bound?>,
    ): Bound? {
        if (!budget.charge(allocation = row.terms.size.toLong() * 2 + 4)) return null
        val premises = ArrayList<SearchAtomPremise>()
        premises.add(row.premise)
        var residual = row.rhs
        var strict = false
        for (term in row.terms) {
            if (term.column == target.column) continue
            val side = selected(term, minimum, lower, upper) ?: return null
            if (!budget.arithmetic(term.coefficient, side.side.number.value)) return null
            val product = term.coefficient * side.side.number.value
            if (!budget.arithmetic(residual, product)) return null
            residual -= product
            strict = strict || side.side.strict
            premises.add(side.premise)
        }
        if (!budget.arithmetic(residual, target.coefficient)) return null
        val value = residual * target.coefficient.reciprocal()
        return Bound(ExactLpSide(ExactLpNumber.of(value), strict), SearchAtomPremise.All(premises), true)
    }

    private fun finish(lower: List<Bound?>, upper: List<Bound?>, conflict: SearchAtomPremise? = null): Pass? {
        if (!budget.charge(allocation = lower.size.toLong() * 8)) return null
        val result = ArrayList<RowImplication>()
        for (column in lower.indices) {
            lower[column]?.takeIf { it.derived }?.let { result.add(RowImplication(column, false, it.side, it.premise)) }
            upper[column]?.takeIf { it.derived }?.let { result.add(RowImplication(column, true, it.side, it.premise)) }
        }
        return Pass(result, conflict, lower.toList(), upper.toList())
    }
}
