package com.eignex.klause.lowering

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearObjectiveSpec
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.Term
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.div
import com.eignex.klause.util.times
import com.eignex.klause.util.gcd
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.toLongExact
import com.eignex.klause.util.unaryMinus

internal class LinearSourceView(
    val source: LinearSourceProblem,
    val model: Problem,
    val objective: LinearObjectiveSpec?,
    val columns: IntArray,
    val sourceIntCount: Int,
    val sourceRealCount: Int,
    val exact: Boolean,
    val objectiveScale: BigFraction,
)

internal fun lowerLinearSource(
    source: LinearSourceProblem,
    settings: ProblemSettings,
    numerical: Boolean,
    stop: Cancellation,
): LinearSourceView? {
    if (stop()) return null
    val floating = numerical && source.variableValues.none { it.integer } && source.canProjectToDouble(stop)
    if (stop()) return null
    var integers = 0
    var reals = 0
    val columns = IntArray(source.variableValues.size)
    for (index in columns.indices) {
        stop.charge(1L)
        if (stop()) return null
        columns[index] = if (source.variableValues[index].integer) {
            Term.ofIntVar(integers++)
        } else Term.ofRealVar(reals++)
    }
    val sourceReals = reals
    val lower = LongArray(integers)
    val upper = LongArray(integers)
    val openLower = Bits(integers)
    val openUpper = Bits(integers)
    val realLower = DoubleArray(reals) { Double.NEGATIVE_INFINITY }
    val realUpper = DoubleArray(reals) { Double.POSITIVE_INFINITY }
    val factors = ArrayList<Factor>()
    var exact = true
    fun projected(value: BigFraction): Double {
        val result = value.toDouble()
        if (BigFraction.ofDouble(result) != value) exact = false
        return result
    }
    fun row(terms: List<LinearSourceTerm>, relation: LinearOp, bound: BigFraction): LinearRow {
        val refs = IntArray(terms.size) { columns[terms[it].column] }
        if (!floating || terms.isEmpty()) return ExactLinear.of(refs, terms.map { it.coefficient }, relation, bound)
        return Linear(
            intArrayOf(), doubleArrayOf(), IntArray(refs.size) { Term.realVar(refs[it]) },
            DoubleArray(terms.size) { projected(terms[it].coefficient) }, relation, projected(bound),
        )
    }
    for ((index, variable) in source.variableValues.withIndex()) {
        stop.charge(1L)
        if (stop()) return null
        val reference = columns[index]
        if (floating) {
            val id = Term.realVar(reference)
            variable.lower?.let { realLower[id] = projected(it) }
            variable.upper?.let { realUpper[id] = projected(it) }
            if (realLower[id] > realUpper[id]) {
                factors += row(listOf(LinearSourceTerm(index, BigFraction.ONE)), LinearOp.LE,
                    requireNotNull(variable.upper)) as Factor
                realUpper[id] = realLower[id]
            }
        } else {
            val terms = listOf(LinearSourceTerm(index, BigFraction.ONE))
            variable.lower?.let { factors += row(terms, LinearOp.GE, it) as Factor }
            variable.upper?.let { factors += row(terms, LinearOp.LE, it) as Factor }
            if (variable.integer) {
                val id = Term.intVar(reference)
                val lo = variable.lower?.ceilInteger()?.takeIf { it.fitsLong() }
                val hi = variable.upper?.negated()?.ceilInteger()?.let { -it }?.takeIf { it.fitsLong() }
                if (lo == null) openLower.set(id) else lower[id] = lo.toLongExact()
                if (hi == null) openUpper.set(id) else upper[id] = hi.toLongExact()
                if (lo != null && hi != null && lower[id] > upper[id]) upper[id] = lower[id]
            }
        }
    }
    for (original in source.rowValues) {
        stop.charge(original.termValues.size.toLong() + 1L)
        if (stop()) return null
        fun add(bound: BigFraction, relation: LinearOp) {
            val comparison = row(original.termValues, relation, bound)
            val guard = original.indicator
            factors += if (guard == null) comparison as Factor else {
                LinearIndicator(Term.intVar(columns[guard.column]), guard.whenOne, comparison as ExactLinear)
            }
        }
        if (original.lower != null && original.lower == original.upper) add(original.lower, LinearOp.EQ) else {
            original.lower?.let { add(it, LinearOp.GE) }
            original.upper?.let { add(it, LinearOp.LE) }
        }
    }
    var objectiveScale = BigFraction.ONE
    val objective = source.objective?.let { original ->
        val integral = MutableList(integers) { BigFraction.ZERO }
        var onlyIntegers = true
        for (term in original.termValues) {
            stop.charge(1L)
            if (stop()) return null
            val reference = columns[term.column]
            if (Term.isReal(reference)) onlyIntegers = false else integral[Term.intVar(reference)] += term.coefficient
        }
        var multiplier = BIG_ONE
        if (onlyIntegers) {
            for (value in integral + original.constant) {
                stop.charge(1L)
                if (stop()) return null
                multiplier = (multiplier / multiplier.gcd(value.den)) * value.den
                if (!multiplier.fitsLong()) break
            }
        }
        val scale = BigFraction.of(multiplier, BIG_ONE)
        val scaled = if (onlyIntegers && multiplier.fitsLong()) integral.map { it * scale } else null
        val constant = original.constant * scale
        if (scaled != null && constant.fitsObjectiveLong() && scaled.all { it.fitsObjectiveLong() }) {
            objectiveScale = scale
            LinearObjectiveSpec(intCoefficients = LongArray(integers) { scaled[it].num.toLongExact() },
                constant = constant.num.toLongExact())
        } else {
            val auxiliary = reals++
            val refs = IntArray(original.termValues.size + 1) { index ->
                if (index == original.termValues.size) Term.ofRealVar(auxiliary) else {
                    columns[original.termValues[index].column]
                }
            }
            val coefficients = original.termValues.map { it.coefficient } + BigFraction.MINUS_ONE
            factors += if (floating) {
                Linear(intArrayOf(), doubleArrayOf(), IntArray(refs.size) { Term.realVar(refs[it]) },
                    DoubleArray(coefficients.size) { projected(coefficients[it]) }, LinearOp.EQ,
                    projected(original.constant.negated()))
            } else {
                ExactLinear.of(refs, coefficients, LinearOp.EQ, original.constant.negated())
            }
            LinearObjectiveSpec(realCoefficients = DoubleArray(reals) { if (it == auxiliary) 1.0 else 0.0 })
        }
    }
    if (stop()) return null
    val model = Problem(
        settings = settings,
        semantics = source.semantics,
        numBoolVars = 0,
        intBounds = IntBounds.fromModelBounds(lower, upper, openLower, openUpper),
        factors = factors.toTypedArray(),
        numRealVars = reals,
        realLower = DoubleArray(reals) { if (it < realLower.size) realLower[it] else Double.NEGATIVE_INFINITY },
        realUpper = DoubleArray(reals) { if (it < realUpper.size) realUpper[it] else Double.POSITIVE_INFINITY },
    )
    return LinearSourceView(source, model, objective, columns, integers, sourceReals, exact, objectiveScale)
        .takeUnless { stop() }
}

private fun BigFraction.fitsObjectiveLong(): Boolean =
    den == BIG_ONE && num.fitsLong() && num.toLongExact() != Long.MIN_VALUE

private fun LinearSourceProblem.canProjectToDouble(stop: Cancellation): Boolean {
    fun supported(value: BigFraction?): Boolean {
        stop.charge(1L)
        if (stop()) return false
        if (value == null) return true
        val projected = value.toDouble()
        return projected.isFinite() && (value.isZero || projected != 0.0)
    }
    return variableValues.all { supported(it.lower) && supported(it.upper) } &&
        rowValues.all { row -> supported(row.lower) && supported(row.upper) &&
            row.termValues.all { supported(it.coefficient) } } &&
        (objective?.let { objective ->
            supported(objective.constant) && objective.termValues.all { supported(it.coefficient) }
        } != false)
}
