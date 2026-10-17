package com.eignex.klause.formats.smtlib

import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.LinearObjectiveSpec
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.toLongExact

internal fun captureSmtSourceObjective(
    commands: List<SExpr>,
    cancellation: Cancellation,
    semantics: EffectiveSourceSemantics,
): SmtLibPolynomialProblem {
    val compiler = SmtLibPolynomialCompiler(cancellation, allowIntegers = true,
        allowFunctions = true, semantics = semantics)
    for (command in commands) {
        cancellation.polynomialSourceCheckpoint(1)
        val head = ((command as? SExpr.SList)?.items?.firstOrNull() as? SExpr.Atom)?.text
        if (head in setOf("declare-const", "declare-fun", "define-fun", "minimize", "maximize")) {
            compiler.command(command)
        }
    }
    return compiler.build().also {
        if (it.transcendental != null) smtUnsupported("function objective requires its source expression")
        if (it.objective == null) smtUnsupported("objective requires an exact source expression")
    }
}

internal fun SmtLibPolynomialProblem.bindObjective(
    source: SmtLibProblem,
    cancellation: Cancellation,
): SmtLibProblem {
    fun mapping(names: Map<String, Int>, retained: Map<String, Int>): IntArray {
        val canonical = retained.mapKeys { polynomialSymbol(it.key) }
        return names.keys.map { cancellation.polynomialSourceCheckpoint(); canonical.getValue(it) }.toIntArray()
    }
    val remap = VarRemap(mapping(boolVarNames, source.boolVarNames), mapping(intVarNames, source.intVarNames),
        mapping(realVarNames, source.realVarNames))
    val original = requireNotNull(objective).remap(remap)
    val affine = original.affineObjective(source, cancellation)
    return source.copy(objective = affine, quadraticObjective = if (affine == null) original else null)
}

internal fun SmtLibProblem.hasDeclaredAffineObjective(): Boolean {
    val objective = objective ?: return false
    fun declared(values: LongArray, names: Map<String, Int>): Boolean {
        val ids = names.values.toSet()
        return values.indices.all { values[it] == 0L || it in ids }
    }
    val reals = realVarNames.values.toSet()
    return declared(objective.intCoefficientValues, intVarNames) &&
        declared(objective.boolWeightValues, boolVarNames) && objective.realCoefficientValues.indices.all {
            objective.realCoefficientValues[it] == 0.0 || it in reals
        }
}

private fun QuadraticObjectiveSpec.affineObjective(
    source: SmtLibProblem,
    cancellation: Cancellation,
): LinearObjectiveSpec? {
    if (constant.denominator != BIG_ONE || !constant.numerator.fitsLong()) return null
    val booleans = LongArray(source.model.numBoolVars)
    val integers = LongArray(source.model.numIntVars)
    val reals = DoubleArray(source.model.numRealVars)
    val coefficients = LinkedHashMap<QuadraticVariable, BigFraction>()
    for (term in terms) {
        cancellation.polynomialSourceCheckpoint(1)
        if (term.second != null) return null
        val value = BigFraction.of(term.coefficient.numerator, term.coefficient.denominator)
        coefficients[term.first] = (coefficients[term.first] ?: BigFraction.ZERO) + value
    }
    for ((variable, coefficient) in coefficients) {
        cancellation.polynomialSourceCheckpoint(1)
        when (variable) {
            is QuadraticVariable.Real -> {
                val value = coefficient.toDouble()
                if (BigFraction.ofDouble(value) != coefficient) return null
                reals[variable.id.index] = value
            }
            is QuadraticVariable.Int, is QuadraticVariable.Bool -> {
                if (coefficient.den != BIG_ONE || !coefficient.num.fitsLong()) return null
                val value = coefficient.num.toLongExact()
                if (variable is QuadraticVariable.Int) integers[variable.id.index] = value
                else booleans[(variable as QuadraticVariable.Bool).id.index] = value
            }
        }
    }
    return LinearObjectiveSpec(booleans, integers, constant.numerator.toLongExact(), reals)
}
