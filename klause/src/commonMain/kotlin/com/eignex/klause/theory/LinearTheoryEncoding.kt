package com.eignex.klause.theory

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation

internal class EncodedTheoryFactor(
    val source: Factor,
    val sourceFactorIndex: Int,
    override val linearForm: LinearForm,
    val integerConditions: Map<SourceIntId, BigInt>,
) : Factor by source

internal fun PreparedFactorProjections.linearTheoryModel(
    target: EncodingTarget,
    indices: List<Int> = problem.factors.indices.toList(),
    fixedIntegers: Map<SourceIntId, BigInt> = emptyMap(),
    cancellation: Cancellation = Cancellation.Never,
    fixedBooleans: Map<SourceBoolId, Boolean> = emptyMap(),
): Problem? {
    require(target != EncodingTarget.POLYNOMIAL)
    val factors = ArrayList<Factor>(indices.size)
    for (index in indices) {
        val source = problem.factors[index]
        val encoding = LinearTheoryEncoding(target, index, fixedIntegers, fixedBooleans, cancellation)
        if (encoder(index, target).install(source, encoding) != null) return null
        val form = encoding.form ?: return null
        factors += if (form === source.linearForm && fixedIntegers.isEmpty()) source else {
            EncodedTheoryFactor(source, index, form, fixedIntegers.toMap())
        }
    }
    return problem.withFactors(factors.toTypedArray(),
        problem.impliedFactorMask?.let { mask -> BooleanArray(indices.size) { mask[indices[it]] } })
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
