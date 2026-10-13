package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.real.ExactRealValue
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.presolve.SourceMapping
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.AssembledSearchModel
import com.eignex.klause.solver.search.SearchBoolValue
import com.eignex.klause.solver.search.SearchExactAlgebraicRealValue
import com.eignex.klause.solver.search.SearchExactIntValue
import com.eignex.klause.solver.search.SearchExactRealExpressionValue
import com.eignex.klause.solver.search.SearchExactRealValue
import com.eignex.klause.util.BigInt

internal class SourceWitnessAssembler(
    private val source: Problem,
    private val originalSource: Problem = source,
) {
    fun assemble(model: AssembledSearchModel, mapping: SourceMapping): OpenTheoryAssignment? {
        if (mapping.target !== source || mapping.source !== originalSource ||
            mapping.source.numBoolVars != source.numBoolVars ||
            mapping.source.numIntVars != source.numIntVars || mapping.source.numRealVars != source.numRealVars ||
            !model.numericCoordinatesFit(source.numIntVars, source.numRealVars)
        ) return null
        val bools = BooleanArray(source.numBoolVars) { model.valueOf(SearchBoolValue(it)) ?: return null }
        val ints = Array(source.numIntVars) {
            model.valueOf(SearchExactIntValue(SourceIntId(it))) ?: return null
        }
        val reals = List(source.numRealVars) { variable ->
            val rational = model.valueOf(SearchExactRealValue(SourceRealId(variable)))
            val algebraic = model.valueOf(SearchExactAlgebraicRealValue(SourceRealId(variable)))
            val expression = model.valueOf(SearchExactRealExpressionValue(SourceRealId(variable)))
            var representations = 0
            if (rational != null) representations++
            if (algebraic != null) representations++
            if (expression != null) representations++
            if (representations != 1) return null
            when {
                rational != null -> SourceRealCoordinate.Rational(rational)
                algebraic != null -> SourceRealCoordinate.Algebraic(algebraic)
                else -> SourceRealCoordinate.Expression(requireNotNull(expression))
            }
        }
        return mapping.lift(SourceCoordinateWitness(bools, ints, reals))
    }
}

internal sealed interface SourceRealCoordinate {
    val rational: BigFraction?
    val algebraic: AlgebraicReal? get() = null

    class Rational(override val rational: BigFraction) : SourceRealCoordinate

    class Algebraic(override val algebraic: AlgebraicReal) : SourceRealCoordinate {
        override val rational: BigFraction? get() = algebraic.rational
    }

    class Expression(val value: ExactRealValue) : SourceRealCoordinate {
        override val rational: BigFraction? get() = value.rational
        override val algebraic: AlgebraicReal? get() = value.algebraic
    }
}

internal class SourceCoordinateWitness(
    bools: BooleanArray,
    ints: Array<BigInt>,
    reals: List<SourceRealCoordinate>,
) : OpenTheoryAssignment {
    private val bools = bools.copyOf()
    private val ints = ints.copyOf()
    private val reals = reals.toList()

    override fun boolValue(id: Int): Boolean = bools[id]
    override fun intValue(id: Int): String = ints[id].toString()
    override fun realValue(id: Int): String = when (val value = reals[id]) {
        is SourceRealCoordinate.Rational -> value.rational.toString()
        is SourceRealCoordinate.Algebraic -> value.algebraic.rational?.toString()
            ?: "root(${value.algebraic.rootIndex};[${value.algebraic.definingCoefficients.joinToString(",")}])"
        is SourceRealCoordinate.Expression -> value.value.expression.exactRealText()
    }

    fun integer(id: Int): BigInt = ints[id]
    fun real(id: Int): SourceRealCoordinate = reals[id]
}
