package com.eignex.klause.theory

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Term
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation

internal class EncodedTheoryFactor(
    val source: Factor,
    override val linearForm: LinearForm,
    val attribution: EncodingAttribution,
) : Factor by source

internal fun PreparedFactorProjections.linearTheoryModel(
    target: EncodingTarget,
    indices: List<Int> = problem.factors.indices.toList(),
    fixedIntegers: Map<SourceIntId, BigInt> = emptyMap(),
    cancellation: Cancellation = Cancellation.Never,
    fixedBooleans: Map<SourceBoolId, Boolean> = emptyMap(),
): Problem? {
    require(target != EncodingTarget.POLYNOMIAL)
    val integerPins = fixedIntegers.toMap()
    val booleanPins = fixedBooleans.toMap()
    val factors = ArrayList<Factor>(indices.size)
    for (index in indices) {
        val relation = if (integerPins.isEmpty() && booleanPins.isEmpty() && cancellation === Cancellation.Never) {
            preparedLinearEncoding(index, target)
        } else installLinearFactor(index, target, integerPins, booleanPins, cancellation)
        factors += relation ?: return null
    }
    return problem.withFactors(factors.toTypedArray(),
        problem.impliedFactorMask?.let { mask -> BooleanArray(indices.size) { mask[indices[it]] } })
}

internal fun PreparedFactorProjections.installLinearFactor(
    index: Int,
    target: EncodingTarget,
    integerPins: Map<SourceIntId, BigInt> = emptyMap(),
    booleanPins: Map<SourceBoolId, Boolean> = emptyMap(),
    cancellation: Cancellation = Cancellation.Never,
): EncodedTheoryFactor? {
    val source = problem.factors[index]
    val sourceIndex = (source as? EncodedTheoryFactor)?.attribution?.sourceFactorIndex ?: index
    val encoding = LinearTheoryEncoding(target, sourceIndex, integerPins, booleanPins, cancellation)
    if (encoder(index, target).install(source, encoding) != null) return null
    val form = encoding.form ?: return null
    val booleans = source.boolVars.toSet()
    val integers = source.intVars.toSet()
    val reals = source.variables.reals.toSet()
    require(form.rows.all { row ->
        (row.activator == LinearRow.ALWAYS || row.activator in booleans) &&
            (0 until row.size).all { term ->
                val ref = row.ref(term)
                when {
                    Term.isInt(ref) -> Term.intVar(ref) in integers
                    Term.isReal(ref) -> Term.realVar(ref) in reals
                    else -> (Term.lit(ref) ushr 1) in booleans
                }
            }
    }) { "an encoding must retain the source factor's coordinate membership" }
    if (source is EncodedTheoryFactor && form === source.linearForm &&
        integerPins.isEmpty() && booleanPins.isEmpty()
    ) return source
    return EncodedTheoryFactor(source, form, EncodingAttribution(sourceIndex,
        source.boolVars.map(::SourceBoolId), source.intVars.map(::SourceIntId),
        source.variables.reals.map(::SourceRealId), integerConditions = integerPins,
        booleanConditions = booleanPins))
}

private class LinearTheoryEncoding(
    override val target: EncodingTarget,
    override val sourceFactorIndex: Int,
    private val fixedIntegers: Map<SourceIntId, BigInt>,
    private val fixedBooleans: Map<SourceBoolId, Boolean>,
    override val cancellation: Cancellation,
) : TheoryEncoding {
    var form: LinearForm? = null
        private set

    override fun fixedInteger(id: SourceIntId): BigInt? = fixedIntegers[id]

    override fun fixedBoolean(id: SourceBoolId): Boolean? = fixedBooleans[id]

    override fun linear(form: LinearForm) {
        require(this.form == null) { "a linear encoder must install one complete relation" }
        require(form !is LinearForm.Relaxation) { "a relaxation cannot enforce a source factor" }
        this.form = form
    }

    override fun polynomial(formula: PolynomialRealFormula): Unit = error("linear target cannot install a polynomial")
    override fun integer(id: SourceIntId): PolynomialExpression = error("linear target uses source integer terms")
    override fun real(id: SourceRealId): PolynomialExpression = error("linear target uses source real terms")
    override fun conditional(
        condition: PolynomialRealFormula,
        whenTrue: PolynomialExpression,
        whenFalse: PolynomialExpression,
    ): PolynomialExpression = error("linear target uses source Boolean guards")
}
