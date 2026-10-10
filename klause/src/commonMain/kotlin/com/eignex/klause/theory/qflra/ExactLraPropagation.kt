package com.eignex.klause.theory.qflra

import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.linearRows
import com.eignex.klause.lp.ExactComparison
import com.eignex.klause.lp.ExactRowForm
import com.eignex.klause.lp.asFraction
import com.eignex.klause.lp.bounding.LpPropagator
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpBoundAssertion
import com.eignex.klause.lp.engine.strongerThan
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.explainAtoms
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.minus
import com.eignex.klause.util.negate
import com.eignex.klause.util.plus

internal class ExactLraPropagation(
    private val model: Problem,
    private val lp: LpPropagator,
    private val system: LiveQfLraSystem,
    forms: List<List<ExactRowForm>>,
) {
    private val rows = model.factors.flatMapIndexed { factorIndex, factor ->
        if (factor.linearForm is LinearForm.Disjunction) emptyList() else {
            factor.linearRows.mapIndexed { rowIndex, row ->
                PreparedRow(row, forms[factorIndex][rowIndex], row.booleanVariables())
            }
        }
    }
    private val queue = ArrayDeque<Int>().apply { addAll(rows.indices) }
    private val queued = BooleanArray(rows.size) { true }
    private val columnReaders = Array(requireNotNull(lp.state).model.numVars) { ArrayList<Int>() }.also { readers ->
        rows.forEachIndexed { index, row ->
            val comparison = row.form.comparison(true) { false }
            for (column in comparison.terms.keys) readers[column] += index
            system.termColumn(comparison)?.let { readers[it] += index }
        }
    }
    private val booleanReaders = Array(model.numBoolVars) { ArrayList<Int>() }.also { readers ->
        rows.forEachIndexed { index, row -> for (variable in row.variables) readers[variable] += index }
    }
    private val lower = arrayOfNulls<LpBoundAssertion>(columnReaders.size)
    private val upper = arrayOfNulls<LpBoundAssertion>(columnReaders.size)
    private val booleans = arrayOfNulls<Boolean>(model.numBoolVars)
    var implied = false
        private set

    fun propagate(context: SearchContext, stop: Cancellation): ComponentResult {
        implied = false
        // Interval narrowing may converge only asymptotically on real rows. The LP remains the complete check.
        for (column in columnReaders.indices) refreshColumn(column)
        for (variable in booleans.indices) {
            val value = context.boolValue(variable)
            if (value != booleans[variable]) {
                booleans[variable] = value
                booleanReaders[variable].forEach(::enqueue)
            }
        }
        var visits = 0
        while (queue.isNotEmpty() && visits++ < rows.size * 4) {
                if (stop()) return ComponentResult.Indeterminate
                val rowIndex = queue.removeFirst()
                queued[rowIndex] = false
                val (row, form, variables) = rows[rowIndex]
                if (variables.any { it != row.activator && context.boolValue(it) == null }) continue
                if (context.boolValue(row.activator) == null && (0 until row.size).any {
                        Term.isBool(row.ref(it)) && Lit.variable(Term.lit(row.ref(it))) == row.activator
                    }
                ) continue
                val comparison = form.comparison(true) { context.boolValue(it) == true }
                val minimum = stronger(
                    activity(comparison.terms, upper = false), system.activityBound(comparison, false), false,
                )
                val maximum = stronger(
                    activity(comparison.terms, upper = true), system.activityBound(comparison, true), true,
                )
                val truth = comparison.truth(minimum, maximum)
                val activated = row.activator == LinearRow.ALWAYS || context.boolValue(row.activator) == true
                val assigned = row.activator == LinearRow.ALWAYS || context.boolValue(row.activator) != null
                val booleans = variables.filter { it != row.activator }.map {
                    SearchAtomPremise.Asserted(SearchDecision.Bool(Lit.make(it, context.boolValue(it) == true)))
                }
                if (!assigned && truth != null) {
                    val literal = Lit.make(row.activator, truth.value)
                    val reason = context.explainAtoms(
                        SearchAtomPremise.All(booleans + truth.premise), SearchDecision.Bool(literal),
                    ) ?: continue
                    val result = context.imply(literal, reason)
                    if (result !is ComponentResult.Consistent) return result
                    implied = true
                    booleanReaders[row.activator].forEach(::enqueue)
                    continue
                }
                if (!assigned) continue
                val premise = SearchAtomPremise.All(
                    booleans + if (row.activator == LinearRow.ALWAYS) emptyList() else {
                        listOf(SearchAtomPremise.Asserted(SearchDecision.Bool(Lit.make(row.activator, activated))))
                    },
                )
                if (truth != null && truth.value != activated) {
                    return ComponentResult.Conflict(
                        context.explainAtoms(SearchAtomPremise.All(listOf(premise, truth.premise))),
                    )
                }
                val active = if (activated) comparison else form.comparison(false) { context.boolValue(it) == true }
                if (active.op == LinearOp.NE) continue
                val inequalities = ArrayList<ExactRationalInequality>(2)
                active.rowsInto(inequalities)
                for (inequality in inequalities) {
                    for (index in inequality.columns.indices) {
                        if (stop()) {
                            enqueue(rowIndex)
                            return ComponentResult.Indeterminate
                        }
                        val column = inequality.columns[index]
                        val coefficient = inequality.coefficients[index]
                        val rest = activity(
                            inequality.columns.indices.filter { it != index }.associate {
                                inequality.columns[it] to inequality.coefficients[it]
                            }, upper = false,
                        ) ?: continue
                        var bound = (inequality.rhs - rest.value) * coefficient.reciprocal()
                        var strict = inequality.strict || rest.strict
                        val upper = coefficient.signum() > 0
                        if (column >= model.numRealVars) {
                            bound = integerBound(bound, upper, strict)
                            strict = false
                        }
                        val previous = lp.state?.activeSide(column, upper)?.side
                        val side = ExactLpSide(ExactLpNumber.of(bound), strict)
                        if (previous != null && !side.strongerThan(previous, upper)) continue
                        val reason = SearchAtomPremise.All(listOf(premise, rest.premise))
                        if (!lp.assertBound(column, upper, side, reason)) {
                            return ComponentResult.Indeterminate
                        }
                        if (lp.state?.conflict != null) {
                            return ComponentResult.Conflict(context.explainAtoms(SearchAtomPremise.All(listOf(
                                lp.activeBoundPremise(column, false) ?: SearchAtomPremise.Unavailable,
                                lp.activeBoundPremise(column, true) ?: SearchAtomPremise.Unavailable,
                            ))))
                        }
                        refreshColumn(column)
                    }
                }
        }
        return ComponentResult.Consistent
    }

    private fun enqueue(row: Int) {
        if (!queued[row]) {
            queued[row] = true
            queue.addLast(row)
        }
    }

    private fun integerBound(value: BigFraction, upper: Boolean, strict: Boolean): BigFraction {
        val rounded = if (upper) value.negated().ceilInteger().negate() else value.ceilInteger()
        val adjustment = if (strict && value.den == BIG_ONE) BIG_ONE else BIG_ZERO
        return (if (upper) rounded - adjustment else rounded + adjustment).asFraction()
    }

    private fun refreshColumn(column: Int) {
        val state = lp.state ?: return
        val nextLower = state.activeSide(column, false)
        val nextUpper = state.activeSide(column, true)
        if (nextLower !== lower[column] || nextUpper !== upper[column]) {
            lower[column] = nextLower
            upper[column] = nextUpper
            columnReaders[column].forEach(::enqueue)
        }
    }

    private fun stronger(first: SmtActivityBound?, second: SmtActivityBound?, upper: Boolean): SmtActivityBound? {
        if (first == null) return second
        if (second == null) return first
        val difference = second.value.compareTo(first.value)
        return if ((if (upper) difference < 0 else difference > 0) ||
            (difference == 0 && second.strict && !first.strict)
        ) second else first
    }

    private fun activity(terms: Map<Int, BigFraction>, upper: Boolean): SmtActivityBound? {
        var value = BigFraction.ZERO
        var strict = false
        val premises = ArrayList<SearchAtomPremise>(terms.size)
        for ((column, coefficient) in terms) {
            if (coefficient.isZero) continue
            val sideUpper = if (coefficient.signum() > 0) upper else !upper
            val side = lp.state?.activeSide(column, sideUpper)?.side ?: return null
            value += coefficient * side.number.value
            strict = strict || side.strict
            premises += lp.activeBoundPremise(column, sideUpper) ?: SearchAtomPremise.Unavailable
        }
        return SmtActivityBound(value, strict, SearchAtomPremise.All(premises))
    }

    private fun ExactComparison.truth(minimum: SmtActivityBound?, maximum: SmtActivityBound?): Truth? {
        val below = maximum?.let { it.value < bound || (it.value == bound && (!strict || it.strict)) } == true
        val above = minimum?.let { it.value > bound || (it.value == bound && (!strict || it.strict)) } == true
        val excludedBelow = maximum?.let { it.value < bound || (it.value == bound && it.strict) } == true
        val excludedAbove = minimum?.let { it.value > bound || (it.value == bound && it.strict) } == true
        val fixed = minimum != null && maximum != null && minimum.value == bound && maximum.value == bound &&
            !minimum.strict && !maximum.strict
        return when (op) {
            LinearOp.LE -> when {
                below -> Truth(true, checkNotNull(maximum).premise)
                minimum != null && (minimum.value > bound || (minimum.value == bound && (strict || minimum.strict))) ->
                    Truth(false, minimum.premise)
                else -> null
            }
            LinearOp.GE -> when {
                above -> Truth(true, checkNotNull(minimum).premise)
                maximum != null && (maximum.value < bound || (maximum.value == bound && (strict || maximum.strict))) ->
                    Truth(false, maximum.premise)
                else -> null
            }
            LinearOp.EQ, LinearOp.NE -> when {
                fixed -> Truth(op == LinearOp.EQ, SearchAtomPremise.All(listOf(
                    checkNotNull(minimum).premise, checkNotNull(maximum).premise,
                )))
                excludedBelow -> Truth(op == LinearOp.NE, checkNotNull(maximum).premise)
                excludedAbove -> Truth(op == LinearOp.NE, checkNotNull(minimum).premise)
                else -> null
            }
        }
    }

    private data class PreparedRow(val row: LinearRow, val form: ExactRowForm, val variables: Set<Int>)
    private data class Truth(val value: Boolean, val premise: SearchAtomPremise)
}
