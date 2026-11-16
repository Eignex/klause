package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.lowering.PolynomialRealLowering
import com.eignex.klause.theory.EncodingAttribution
import com.eignex.klause.theory.EncodingTarget
import com.eignex.klause.theory.TheoryEncoding
import com.eignex.klause.theory.TheoryRealId
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigInt

internal class PolynomialEncodingContext(
    override val sourceFactorIndex: Int,
    private val factor: Factor,
    private val lowering: PolynomialRealLowering,
    override val cancellation: ExecutionControl,
    private val integerCoordinate: (Int) -> Int,
    private val realCoordinate: (Int) -> Int,
    private val fixedIntegers: Map<SourceIntId, BigInt> = emptyMap(),
    private val fixedBooleans: Map<SourceBoolId, Boolean> = emptyMap(),
) : TheoryEncoding {
    override val target = EncodingTarget.POLYNOMIAL
    val formulas = ArrayList<PolynomialRealFormula>()
    private val integerMapping = LinkedHashMap<SourceIntId, TheoryRealId>()
    private val realMapping = LinkedHashMap<SourceRealId, TheoryRealId>()
    private val auxiliaries = ArrayList<TheoryRealId>()

    override fun fixedInteger(id: SourceIntId): BigInt? = fixedIntegers[id]
    override fun fixedBoolean(id: SourceBoolId): Boolean? = fixedBooleans[id]
    override fun linear(form: LinearForm): Unit = error("polynomial target requires a formula")

    private fun coordinates(): Set<Int> =
        (integerMapping.values + realMapping.values + auxiliaries).map { it.index }.toSet()

    override fun polynomial(formula: PolynomialRealFormula) {
        validatePolynomialEncodingCoordinates(formula, null, coordinates(), factor.boolVars.toSet(), cancellation)
        formulas += formula
    }

    override fun integer(id: SourceIntId): PolynomialExpression {
        require(id.index in factor.intVars)
        val coordinate = integerCoordinate(id.index)
        integerMapping[id] = TheoryRealId(coordinate)
        return PolynomialExpression.Variable(SourceRealId(coordinate))
    }

    override fun real(id: SourceRealId): PolynomialExpression {
        require(id.index in factor.variables.reals)
        val coordinate = realCoordinate(id.index)
        realMapping[id] = TheoryRealId(coordinate)
        return PolynomialExpression.Variable(SourceRealId(coordinate))
    }

    override fun conditional(
        condition: PolynomialRealFormula,
        whenTrue: PolynomialExpression,
        whenFalse: PolynomialExpression,
    ): PolynomialExpression {
        val coordinates = coordinates()
        val booleans = factor.boolVars.toSet()
        validatePolynomialEncodingCoordinates(condition, null, coordinates, booleans, cancellation)
        validatePolynomialEncodingCoordinates(null, whenTrue, coordinates, booleans, cancellation)
        validatePolynomialEncodingCoordinates(null, whenFalse, coordinates, booleans, cancellation)
        val before = lowering.realCount
        return lowering.conditional(condition, whenTrue, whenFalse).also {
            for (id in before until lowering.realCount) auxiliaries += TheoryRealId(id)
        }
    }

    fun attribution(): EncodingAttribution = EncodingAttribution(
        sourceFactorIndex, factor.boolVars.map(::SourceBoolId), factor.intVars.map(::SourceIntId),
        factor.variables.reals.map(::SourceRealId), integerMapping, realMapping, auxiliaries,
        fixedIntegers, fixedBooleans,
    )
}
