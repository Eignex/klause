package com.eignex.klause.formats.mps

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearObjectiveSpec
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.ir.Term
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Bits
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.toLongExact
import com.eignex.klause.util.unaryMinus

/**
 * Preserve original MPS numbers in exact linear source rows for the shared arithmetic search.
 * Real declarations remain open; their decimal bounds are rows rather than binary64 endpoints.
 * An auxiliary equality preserves fractional objective coefficients, constant and original units.
 */
fun MpsModel.toExactSourceProblem(
    settings: ProblemSettings = KlauseConfig.current.problemSettings(),
    semanticOverrides: SemanticOverrides = SemanticOverrides(feasibilityTolerance = SourceTolerance()),
): MpsCompiled = lowerExactSource(settings, semantics.resolve(semanticOverrides))

internal fun MpsModel.lowerExactSource(
    settings: ProblemSettings,
    effectiveSemantics: EffectiveSourceSemantics,
): MpsCompiled {
    val source = exactAdapterSnapshot()
    val numbers = source.sourceNumbers()
    var intCount = 0
    var realCount = 0
    val columns = source.variables.map { variable ->
        if (variable.integer) MpsColumn(variable.name, false, intCount++)
        else MpsColumn(variable.name, true, realCount++)
    }
    fun ref(column: Int): Int = columns[column].let {
        if (it.real) Term.ofRealVar(it.id) else Term.ofIntVar(it.id)
    }
    val factors = ArrayList<Factor>()
    val lower = LongArray(intCount)
    val upper = LongArray(intCount)
    val openLower = Bits(intCount)
    val openUpper = Bits(intCount)
    for (index in columns.indices) {
        val column = columns[index]
        val (lo, hi) = numbers.variableBounds[index]
        val finiteLo = lo.finiteMps()?.fraction
        val finiteHi = hi.finiteMps()?.fraction
        finiteLo?.let {
            factors += exactSourceRow(intArrayOf(ref(index)), listOf(BigFraction.ONE), LinearOp.GE, it)
        }
        finiteHi?.let {
            factors += exactSourceRow(intArrayOf(ref(index)), listOf(BigFraction.ONE), LinearOp.LE, it)
        }
        if (!column.real) {
            val integerLo = finiteLo?.ceilInteger()?.takeIf { it.fitsLong() }
            val integerHi = finiteHi?.negated()?.ceilInteger()?.let { -it }?.takeIf { it.fitsLong() }
            if (integerLo == null) openLower.set(column.id) else lower[column.id] = integerLo.toLongExact()
            if (integerHi == null) openUpper.set(column.id) else upper[column.id] = integerHi.toLongExact()
            // Exact bound rows retain the crossing; the declaration must permit domain construction.
            if (integerLo != null && integerHi != null && lower[column.id] > upper[column.id]) {
                upper[column.id] = lower[column.id]
            }
        }
    }
    source.constraints.forEachIndexed { index, row ->
        val refs = IntArray(row.indices.size) { ref(row.indices[it]) }
        val coefficients = numbers.constraintCoefficients[index].map { it.fraction }
        val (lo, hi) = numbers.constraintBounds[index]
        fun add(bound: MpsSourceNumber?, relation: LinearOp) {
            val value = bound.finiteMps()?.fraction ?: return
            if (row.indicator == null && coefficients.all { it.isZero }) {
                val holds = when (relation) {
                    LinearOp.GE -> BigFraction.ZERO >= value
                    LinearOp.LE -> BigFraction.ZERO <= value
                    LinearOp.EQ -> BigFraction.ZERO == value
                    LinearOp.NE -> BigFraction.ZERO != value
                }
                if (holds) return
            }
            val comparison = exactSourceRow(refs, coefficients, relation, value)
            val indicator = row.indicator
            factors += if (indicator == null) comparison else {
                require(source.variables[indicator.column].integer) { "MPS indicator must be integral" }
                LinearIndicator(columns[indicator.column].id, indicator.whenOne, comparison)
            }
        }
        if (lo.finiteMps()?.fraction != null && lo.finiteMps()?.fraction == hi.finiteMps()?.fraction) {
            add(lo, LinearOp.EQ)
        } else {
            add(lo, LinearOp.GE)
            add(hi, LinearOp.LE)
        }
    }
    val integralObjective = source.integralSourceObjective(columns, numbers, intCount)
    val objective = when {
        source.objective.name.isEmpty() && source.objective.indices.isEmpty() -> null
        integralObjective != null -> integralObjective
        else -> {
            val objectiveColumn = realCount++
            val refs = source.objective.indices.map(::ref) + Term.ofRealVar(objectiveColumn)
            val coefficients = numbers.objectiveCoefficients.map { it.fraction } + BigFraction.MINUS_ONE
            factors += exactSourceRow(refs.toIntArray(), coefficients, LinearOp.EQ,
                numbers.objectiveConstant.fraction.negated())
            LinearObjectiveSpec(LongArray(0), LongArray(intCount), 0,
                DoubleArray(realCount) { if (it == objectiveColumn) 1.0 else 0.0 })
        }
    }
    val model = Problem(
        settings = settings,
        semantics = effectiveSemantics,
        numBoolVars = 0,
        intBounds = IntBounds.fromModelBounds(lower, upper, openLower, openUpper),
        factors = factors.toTypedArray(),
        numRealVars = realCount,
        realLower = DoubleArray(realCount) { Double.NEGATIVE_INFINITY },
        realUpper = DoubleArray(realCount) { Double.POSITIVE_INFINITY },
    )
    return MpsCompiled(model, objective, source.sense == ObjectiveSense.MAXIMIZE, columns, 1,
        source.variables.count { !it.integer }).withSourceModel(source, null, null)
        .withExactLpModel { source.toExactLpModel() }
}

private fun MpsModel.integralSourceObjective(
    columns: List<MpsColumn>,
    numbers: MpsSourceNumbers,
    intCount: Int,
): LinearObjectiveSpec? {
    val constant = numbers.objectiveConstant.fraction
    if (constant.den != BIG_ONE || !constant.num.fitsLong() || constant.num.toLongExact() == Long.MIN_VALUE) return null
    val coefficients = MutableList(intCount) { BigFraction.ZERO }
    for (entry in objective.indices.indices) {
        val column = columns[objective.indices[entry]]
        if (column.real) return null
        coefficients[column.id] += numbers.objectiveCoefficients[entry].fraction
    }
    if (coefficients.any { it.den != BIG_ONE || !it.num.fitsLong() || it.num.toLongExact() == Long.MIN_VALUE }) {
        return null
    }
    return LinearObjectiveSpec(intCoefficients = LongArray(intCount) { coefficients[it].num.toLongExact() },
        constant = constant.num.toLongExact())
}

private fun exactSourceRow(
    refs: IntArray,
    coefficients: List<BigFraction>,
    relation: LinearOp,
    rhs: BigFraction,
): ExactLinear = ExactLinear.of(refs, coefficients, relation, rhs)
