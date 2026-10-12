package com.eignex.klause.formats.mps

import com.eignex.klause.config.KlauseConfig
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
import com.eignex.klause.util.Bits
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.toLongExact
import com.eignex.klause.util.unaryMinus

/**
 * Preserve original MPS numbers in exact linear source rows for the shared arithmetic search.
 * Real declarations remain open; their decimal bounds are rows rather than binary64 endpoints.
 * An auxiliary objective equality preserves decimal coefficients, constant and original units.
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
            factors += ExactSearchRow.of(intArrayOf(ref(index)), listOf(BigFraction.ONE), LinearOp.GE, it)
        }
        finiteHi?.let {
            factors += ExactSearchRow.of(intArrayOf(ref(index)), listOf(BigFraction.ONE), LinearOp.LE, it)
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
            val comparison = ExactSearchRow.of(refs, coefficients, relation, value)
            val indicator = row.indicator
            factors += if (indicator == null) comparison else {
                require(source.variables[indicator.column].integer) { "MPS indicator must be integral" }
                val trigger = BigFraction.ofLong(if (indicator.whenOne) 1 else 0)
                MpsSourceIndicator(listOf(
                    ExactSearchRow.of(intArrayOf(ref(indicator.column)), listOf(BigFraction.ONE),
                        LinearOp.LE, trigger, strict = true),
                    ExactSearchRow.of(intArrayOf(ref(indicator.column)), listOf(BigFraction.ONE),
                        LinearOp.GE, trigger, strict = true),
                    comparison,
                ))
            }
        }
        add(lo, LinearOp.GE)
        add(hi, LinearOp.LE)
    }
    val objective = if (source.objective.indices.isEmpty()) null else {
        val objectiveColumn = realCount++
        val refs = source.objective.indices.map(::ref) + Term.ofRealVar(objectiveColumn)
        val coefficients = numbers.objectiveCoefficients.map { it.fraction } + BigFraction.MINUS_ONE
        factors += ExactSearchRow.of(refs.toIntArray(), coefficients, LinearOp.EQ,
            numbers.objectiveConstant.fraction.negated())
        LinearObjectiveSpec(LongArray(0), LongArray(intCount), 0,
            DoubleArray(realCount) { if (it == objectiveColumn) 1.0 else 0.0 })
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
