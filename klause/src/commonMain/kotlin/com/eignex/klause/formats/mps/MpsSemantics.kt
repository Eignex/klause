package com.eignex.klause.formats.mps

import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.NumericAuthority
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.ir.UndeclaredIntegerBounds
import com.eignex.klause.ir.UndeclaredRealBounds
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf

/** Decimal authority, wholly unranged declaration defaults and source feasibility slack. */
val mpsSemantics: SourceSemantics = SourceSemantics(
    format = SourceFormat.MPS,
    numericAuthority = NumericAuthority.DECIMAL_LITERALS,
    undeclaredIntegers = UndeclaredIntegerBounds(0, 1),
    undeclaredReals = UndeclaredRealBounds(lower = SemanticRational.ZERO),
    feasibilityTolerance = SourceTolerance(
        absolute = SemanticRational.of(BIG_ONE, bigIntOf(10_000_000L)),
        relative = SemanticRational.of(BIG_ONE, bigIntOf(10_000_000L)),
    ),
)

internal fun MpsModel.effectiveDeclarations(policy: EffectiveSourceSemantics): MpsModel {
    val numbers = sourceNumbers()
    val bounds = checkNotNull(effectiveVariableBounds(policy, numbers, Cancellation.Never))
    val declarations = variables.mapIndexed { index, variable ->
        if (bounds[index] === numbers.variableBounds[index]) variable else {
            variable.copy(lower = bounds[index].first?.double, upper = bounds[index].second?.double)
        }
    }
    return MpsModel(name, sense, objective, declarations, constraints, semantics).withSourceNumbers(MpsSourceNumbers(
        bounds, numbers.constraintCoefficients, numbers.constraintBounds,
        numbers.objectiveCoefficients, numbers.objectiveConstant, numbers.explicitVariableRanges,
    )).exactAdapterSnapshot()
}

internal fun MpsModel.effectiveVariableBounds(
    policy: EffectiveSourceSemantics,
    numbers: MpsSourceNumbers,
    cancellation: Cancellation,
): List<Pair<MpsSourceNumber?, MpsSourceNumber?>>? {
    val bounds = ArrayList<Pair<MpsSourceNumber?, MpsSourceNumber?>>(variables.size)
    for ((index, variable) in variables.withIndex()) {
        cancellation.charge(1L)
        if (cancellation()) return null
        val original = numbers.variableBounds[index]
        if (numbers.explicitVariableRanges[index]) {
            bounds += original
            continue
        }
        val replacement = if (variable.integer) {
            val range = checkNotNull(policy.effective.undeclaredIntegers)
            range.lower?.let { MpsSourceNumber.bound(BigFraction.ofLong(it)) } to
                range.upper?.let { MpsSourceNumber.bound(BigFraction.ofLong(it)) }
        } else {
            val range = checkNotNull(policy.effective.undeclaredReals)
            range.lower?.let { MpsSourceNumber.bound(BigFraction.of(it.numerator, it.denominator)) } to
                range.upper?.let { MpsSourceNumber.bound(BigFraction.of(it.numerator, it.denominator)) }
        }
        bounds += if (original.first.finiteMps()?.fraction == replacement.first?.fraction &&
            original.second.finiteMps()?.fraction == replacement.second?.fraction
        ) original else replacement
    }
    return bounds.takeUnless { cancellation() }
}
