package com.eignex.klause.formats.mps

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearObjectiveSpec
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.MixedVars
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.ExactSearchRow
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Bits
import com.eignex.klause.util.div
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.gcd
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact
import com.eignex.klause.util.unaryMinus

/**
 * Preserve original MPS numbers in exact linear source rows for the shared arithmetic search.
 * Real declarations remain open; their decimal bounds are rows rather than binary64 endpoints.
 * An auxiliary equality preserves fractional objective coefficients, constant and original units.
 */
fun MpsModel.toExactSourceProblem(settings: ProblemSettings = KlauseConfig.current.problemSettings()): MpsCompiled {
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
                val holds = if (relation == LinearOp.GE) BigFraction.ZERO >= value else BigFraction.ZERO <= value
                if (!holds) factors += Clause(IntArray(0))
                return
            }
            val comparison = exactSourceRow(refs, coefficients, relation, value)
            val indicator = row.indicator
            factors += if (indicator == null) comparison else {
                require(source.variables[indicator.column].integer) { "MPS indicator must be integral" }
                val trigger = BigFraction.ofLong(if (indicator.whenOne) 1 else 0)
                MpsSourceIndicator(listOf(
                    exactSourceRow(intArrayOf(ref(indicator.column)), listOf(BigFraction.ONE),
                        LinearOp.LE, trigger, strict = true),
                    exactSourceRow(intArrayOf(ref(indicator.column)), listOf(BigFraction.ONE),
                        LinearOp.GE, trigger, strict = true),
                    comparison,
                ))
            }
        }
        add(lo, LinearOp.GE)
        add(hi, LinearOp.LE)
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
        numBoolVars = 0,
        intBounds = IntBounds.fromModelBounds(lower, upper, openLower, openUpper),
        factors = factors.toTypedArray(),
        numRealVars = realCount,
        realLower = DoubleArray(realCount) { Double.NEGATIVE_INFINITY },
        realUpper = DoubleArray(realCount) { Double.POSITIVE_INFINITY },
    )
    return MpsCompiled(model, objective, source.sense == ObjectiveSense.MAXIMIZE, columns, 1,
        source.variables.count { !it.integer }).withSourceModel(source, null, null)
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
    strict: Boolean = false,
): ExactSearchRow {
    val denominator = coefficients.fold(rhs.den) { scale, coefficient ->
        scale / scale.gcd(coefficient.den) * coefficient.den
    }
    val scale = BigFraction.of(denominator, BIG_ONE)
    return ExactSearchRow.of(refs, coefficients.map { it * scale }, relation, rhs * scale, strict)
}

private class MpsSourceIndicator(private val alternatives: List<ExactSearchRow>) : Factor {
    override val linearForm = LinearForm.Disjunction(alternatives)
    override val variables = MixedVars(
        boundInts = alternatives.flatMap { it.intVars.toList() }.distinct().toIntArray(),
        reals = alternatives.flatMap { it.variables.reals.toList() }.distinct().toIntArray(),
    )

    override fun remap(mapping: VarRemap): Factor =
        MpsSourceIndicator(alternatives.map { it.remap(mapping) as ExactSearchRow })

    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.MPS_SOURCE_INDICATOR) {
        for (row in alternatives) ints(row.structuralKey().toString().map { it.code }.toIntArray())
    }
}
