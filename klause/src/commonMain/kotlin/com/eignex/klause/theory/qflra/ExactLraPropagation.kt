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
    forms: List<List<ExactRowForm>>,
) {
    private val rows = model.factors.flatMapIndexed { factorIndex, factor ->
        if (factor.linearForm is LinearForm.Disjunction) emptyList() else {
            factor.linearRows.mapIndexed { rowIndex, row ->
                PreparedRow(row, forms[factorIndex][rowIndex], row.booleanVariables())
            }
        }
    }
    var implied = false
        private set

    fun propagate(context: SearchContext, stop: Cancellation): ComponentResult {
        implied = false
        // Interval narrowing may converge only asymptotically on real rows. The LP remains the complete check.
        repeat(4) {
            var changed = false
            for ((row, form, variables) in rows) {
                if (stop()) return ComponentResult.Indeterminate
                if (variables.any { it != row.activator && context.boolValue(it) == null }) continue
                if (context.boolValue(row.activator) == null && (0 until row.size).any {
                        Term.isBool(row.ref(it)) && Lit.variable(Term.lit(row.ref(it))) == row.activator
                    }
                ) continue
                val comparison = form.comparison(true) { context.boolValue(it) == true }
                val minimum = activity(comparison.terms, upper = false)
                val maximum = activity(comparison.terms, upper = true)
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
                        if (stop()) return ComponentResult.Indeterminate
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
                            bound = if (upper) {
                                (bound.floor() - if (strict && bound.isInteger()) BIG_ONE else BIG_ZERO)
                                    .asFraction()
                            } else {
                                (bound.negated().floor().negate() +
                                    if (strict && bound.isInteger()) BIG_ONE else BIG_ZERO).asFraction()
                            }
                            strict = false
                        }
                        val previous = lp.state?.activeSide(column, upper)?.side
                        val side = ExactLpSide(ExactLpNumber.of(bound), strict)
                        if (previous != null && !side.strongerThan(previous, upper)) continue
                        if (!lp.assertBound(column, upper, side, SearchAtomPremise.All(listOf(premise, rest.premise)))) {
                            return ComponentResult.Indeterminate
                        }
                        if (lp.state?.conflict != null) {
                            return ComponentResult.Conflict(context.explainAtoms(SearchAtomPremise.All(listOf(
                                lp.activeBoundPremise(column, false) ?: SearchAtomPremise.Unavailable,
                                lp.activeBoundPremise(column, true) ?: SearchAtomPremise.Unavailable,
                            ))))
                        }
                        if (previous != lp.state?.activeSide(column, upper)?.side) changed = true
                    }
                }
            }
            if (!changed || implied) return ComponentResult.Consistent
        }
        return ComponentResult.Consistent
    }

    private fun activity(terms: Map<Int, BigFraction>, upper: Boolean): Activity? {
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
        return Activity(value, strict, SearchAtomPremise.All(premises))
    }

    private fun ExactComparison.truth(minimum: Activity?, maximum: Activity?): Truth? {
        val below = maximum?.let { it.value < bound || it.value == bound && (!strict || it.strict) } == true
        val above = minimum?.let { it.value > bound || it.value == bound && (!strict || it.strict) } == true
        val excludedBelow = maximum?.let { it.value < bound || it.value == bound && it.strict } == true
        val excludedAbove = minimum?.let { it.value > bound || it.value == bound && it.strict } == true
        val fixed = minimum != null && maximum != null && minimum.value == bound && maximum.value == bound &&
            !minimum.strict && !maximum.strict
        return when (op) {
            LinearOp.LE -> when {
                below -> Truth(true, checkNotNull(maximum).premise)
                minimum != null && (minimum.value > bound || minimum.value == bound && (strict || minimum.strict)) ->
                    Truth(false, minimum.premise)
                else -> null
            }
            LinearOp.GE -> when {
                above -> Truth(true, checkNotNull(minimum).premise)
                maximum != null && (maximum.value < bound || maximum.value == bound && (strict || maximum.strict)) ->
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
    private data class Activity(val value: BigFraction, val strict: Boolean, val premise: SearchAtomPremise)
    private data class Truth(val value: Boolean, val premise: SearchAtomPremise)
}
