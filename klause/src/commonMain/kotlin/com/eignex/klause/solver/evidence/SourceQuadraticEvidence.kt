package com.eignex.klause.solver.evidence

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.evaluateExact
import com.eignex.klause.solver.objective.intervalBounds
import com.eignex.klause.util.Cancellation

/** Bound a quadratic source objective over its original declarations and invocation intervals. */
data object SourceQuadraticBoxDerivation : SourceDerivation.Extension

/** Bind exact quadratic objective checks to an original IR source, without inventing an affine objective. */
fun Problem.quadraticSourceProofContext(
    scope: SourceScope,
    objective: QuadraticObjectiveSpec,
    cancellation: Cancellation = Cancellation.Never,
): SourceProofContext? {
    if (scope.objective == null) return null
    for (term in objective.terms) for (variable in listOfNotNull(term.first, term.second)) {
        val declared = when (variable) {
            is QuadraticVariable.Bool -> variable.id.index < numBoolVars
            is QuadraticVariable.Int -> variable.id.index < numIntVars
            is QuadraticVariable.Real -> variable.id.index < numRealVars
        }
        if (!declared) return null
    }
    val base = sourceProofContext(scope.copy(objective = null), cancellation = cancellation) ?: return null
    val original = this
    val witnessChecker = checkNotNull(base.semanticsChecker)
    val checker = object : SourceSemanticsChecker {
        override fun witness(sample: Sample, scope: SourceScope, cancellation: Cancellation): SourceEvidenceCheck =
            witnessChecker.witness(sample, scope, cancellation)

        override fun ray(claim: SourceClaim.Ray, scope: SourceScope, cancellation: Cancellation): SourceEvidenceCheck =
            SourceEvidenceCheck.Unavailable("quadratic source recession derivation unavailable")

        override fun objectiveEquals(
            sample: Sample, value: SourceValue, scope: SourceScope, cancellation: Cancellation,
        ): SourceEvidenceCheck = checkedQuadratic(cancellation) { arithmetic ->
            val checked = witness(sample, scope, cancellation)
            if (checked != SourceEvidenceCheck.Checked) return@checkedQuadratic checked
            val expected = exactQuadraticValue(value, arithmetic) ?: return@checkedQuadratic unavailableQuadratic(
                "quadratic source value has no exact algebraic checker",
            )
            val actual = objective.evaluateExact(sample, arithmetic)
            if (arithmetic.compare(actual, expected) == 0) SourceEvidenceCheck.Checked
            else SourceEvidenceCheck.Rejected("quadratic source objective differs from the claimed value")
        }

        override fun derivation(
            claim: SourceClaim, derivation: SourceDerivation.Extension, scope: SourceScope, cancellation: Cancellation,
        ): SourceEvidenceCheck {
            if (derivation != SourceQuadraticBoxDerivation || claim !is SourceClaim.Bound) {
                return SourceEvidenceCheck.Unavailable("quadratic source derivation checker unavailable")
            }
            return checkedQuadratic(cancellation) { arithmetic ->
                fun range(variable: QuadraticVariable): Pair<BigFraction, BigFraction>? {
                    arithmetic.arithmetic.step()
                    val coordinate = when (variable) {
                        is QuadraticVariable.Bool -> SourceCoordinate(SourceCoordinate.Kind.Boolean, variable.id.index)
                        is QuadraticVariable.Int -> SourceCoordinate(SourceCoordinate.Kind.Integer, variable.id.index)
                        is QuadraticVariable.Real -> SourceCoordinate(SourceCoordinate.Kind.Real, variable.id.index)
                    }
                    if (coordinate !in base.coordinates) return null
                    val originalRange = when (variable) {
                        is QuadraticVariable.Bool -> scope.condition.bools[variable.id.index]?.let {
                            val value = if (it) BigFraction.ONE else BigFraction.ZERO
                            value to value
                        } ?: (BigFraction.ZERO to BigFraction.ONE)
                        is QuadraticVariable.Int -> variable.id.index.let { id ->
                            val lower = if (original.intBounds.hasLower(id)) {
                                BigFraction.ofLong(original.intBounds.lower(id))
                            } else null
                            val upper = if (original.intBounds.hasUpper(id)) {
                                BigFraction.ofLong(original.intBounds.upper(id))
                            } else null
                            lower to upper
                        }
                        is QuadraticVariable.Real -> variable.id.index.let { id ->
                            val lower = BigFraction.ofDouble(original.realLower[id])
                            val upper = BigFraction.ofDouble(original.realUpper[id])
                            lower to upper
                        }
                    }
                    val originalLower = originalRange.first
                    val originalUpper = originalRange.second
                    val lower = scope.condition.lower[coordinate]?.let {
                        if (originalLower == null || it > originalLower) it else originalLower
                    } ?: originalLower
                    val upper = scope.condition.upper[coordinate]?.let {
                        if (originalUpper == null || it < originalUpper) it else originalUpper
                    } ?: originalUpper
                    return (lower ?: return null) to (upper ?: return null)
                }
                val bounds = objective.intervalBounds(::range, arithmetic.arithmetic::step)
                    ?: return@checkedQuadratic unavailableQuadratic("quadratic source intervals are open")
                val value = exactQuadraticValue(claim.value, arithmetic) ?: return@checkedQuadratic unavailableQuadratic(
                    "quadratic source bound has no exact algebraic checker",
                )
                val bound = if (scope.objective?.sense == ObjectiveSense.MINIMIZE) bounds.first else bounds.second
                val comparison = arithmetic.compare(arithmetic.rational(bound), value)
                val establishes = if (scope.objective?.sense == ObjectiveSense.MINIMIZE) comparison > 0
                    else comparison < 0
                if (establishes || (comparison == 0 && !claim.strict)) SourceEvidenceCheck.Checked
                else SourceEvidenceCheck.Rejected("original quadratic intervals do not establish this bound")
            }
        }
    }
    return SourceProofContext(scope, base.coordinates, base.premises, semanticsChecker = checker)
}

private fun exactQuadraticValue(value: SourceValue, arithmetic: AlgebraicArithmetic) = when (value) {
    is SourceValue.Rational -> arithmetic.rational(value.value)
    is SourceValue.Algebraic -> value.value
    is SourceValue.Extension -> null
}

private inline fun checkedQuadratic(
    cancellation: Cancellation, block: (AlgebraicArithmetic) -> SourceEvidenceCheck,
): SourceEvidenceCheck = try {
    val arithmetic = AlgebraicArithmetic(PolynomialArithmetic(cancellation))
    arithmetic.arithmetic.step()
    block(arithmetic).also { arithmetic.arithmetic.step() }
} catch (_: PolynomialInterrupted) {
    SourceEvidenceCheck.Unavailable("quadratic source check cancelled")
} catch (_: PolynomialResourceLimit) {
    SourceEvidenceCheck.Unavailable("quadratic source check arithmetic allowance exhausted")
}

private fun unavailableQuadratic(reason: String): SourceEvidenceCheck = SourceEvidenceCheck.Unavailable(reason)
