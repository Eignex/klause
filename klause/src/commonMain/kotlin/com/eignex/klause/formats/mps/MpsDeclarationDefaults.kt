package com.eignex.klause.formats.mps

import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.simplex.exact.BigFraction

internal fun MpsModel.withDeclarationDefaults(semantics: EffectiveSourceSemantics): MpsModel {
    val numbers = sourceNumbers()
    if (numbers.unrangedVariables.isEmpty()) return this
    val integers = checkNotNull(semantics.effective.undeclaredIntegers)
    val reals = checkNotNull(semantics.effective.undeclaredReals)
    val bounds = numbers.variableBounds.mapIndexed { index, original ->
        if (index !in numbers.unrangedVariables) original else {
            val replacement = if (variables[index].integer) {
                integers.lower?.let(BigFraction::ofLong) to integers.upper?.let(BigFraction::ofLong)
            } else {
                reals.lower?.let { BigFraction.of(it.numerator, it.denominator) } to
                    reals.upper?.let { BigFraction.of(it.numerator, it.denominator) }
            }
            if (original.first.finiteMps()?.fraction == replacement.first &&
                original.second.finiteMps()?.fraction == replacement.second
            ) original else replacement.first?.let(MpsSourceNumber::bound) to
                replacement.second?.let(MpsSourceNumber::bound)
        }
    }
    if (bounds.indices.all { bounds[it] === numbers.variableBounds[it] }) return this
    val declarations = variables.mapIndexed { index, variable ->
        val (lower, upper) = bounds[index]
        if (bounds[index] === numbers.variableBounds[index]) variable else
            variable.copy(lower = lower?.double, upper = upper?.double)
    }
    val source = MpsSourceNumbers(bounds, numbers.constraintCoefficients, numbers.constraintBounds,
        numbers.objectiveCoefficients, numbers.objectiveConstant, unrangedVariables = numbers.unrangedVariables)
    return MpsModel(name, sense, objective, declarations, constraints, this.semantics).withSourceNumbers(source)
}
