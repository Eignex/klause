package com.eignex.klause.solver.evidence

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation

internal fun SourceCondition.checkWitness(
    sample: Sample, cancellation: Cancellation = Cancellation.Never,
): SourceEvidenceCheck = try {
    val arithmetic = AlgebraicArithmetic(PolynomialArithmetic(cancellation))
    arithmetic.arithmetic.step()
    checkWitness(sample, arithmetic).also { arithmetic.arithmetic.step() }
} catch (_: PolynomialInterrupted) {
    SourceEvidenceCheck.Unavailable("source invocation check cancelled")
} catch (_: PolynomialResourceLimit) {
    SourceEvidenceCheck.Unavailable("source invocation arithmetic allowance exhausted")
}

internal fun SourceCondition.checkWitness(
    sample: Sample, arithmetic: AlgebraicArithmetic,
): SourceEvidenceCheck {
    for ((id, value) in bools) {
        arithmetic.arithmetic.step()
        if (id !in 0 until sample.numBoolVars || sample.boolValue(id) != value) {
            return SourceEvidenceCheck.Rejected("witness disagrees with invocation Boolean pins")
        }
    }
    for ((coordinate, value) in lower) {
        arithmetic.arithmetic.step()
        if (!sample.declares(coordinate)) {
            return SourceEvidenceCheck.Rejected("invocation references an undeclared witness coordinate")
        }
        val actual = sample.at(coordinate, arithmetic)
            ?: return SourceEvidenceCheck.Unavailable("invocation bound requires exact real coordinates")
        if (arithmetic.compare(actual, arithmetic.rational(value)) < 0) {
            return SourceEvidenceCheck.Rejected("witness violates an invocation lower bound")
        }
    }
    for ((coordinate, value) in upper) {
        arithmetic.arithmetic.step()
        if (!sample.declares(coordinate)) {
            return SourceEvidenceCheck.Rejected("invocation references an undeclared witness coordinate")
        }
        val actual = sample.at(coordinate, arithmetic)
            ?: return SourceEvidenceCheck.Unavailable("invocation bound requires exact real coordinates")
        if (arithmetic.compare(actual, arithmetic.rational(value)) > 0) {
            return SourceEvidenceCheck.Rejected("witness violates an invocation upper bound")
        }
    }
    return SourceEvidenceCheck.Checked
}

private fun Sample.declares(coordinate: SourceCoordinate): Boolean = when (coordinate.kind) {
    SourceCoordinate.Kind.Boolean -> coordinate.id in 0 until numBoolVars
    SourceCoordinate.Kind.Integer -> coordinate.id in 0 until numIntVars
    SourceCoordinate.Kind.Real -> coordinate.id in 0 until numRealVars
}

private fun Sample.at(coordinate: SourceCoordinate, arithmetic: AlgebraicArithmetic): AlgebraicReal? =
    when (coordinate.kind) {
        SourceCoordinate.Kind.Boolean -> arithmetic.rational(
            if (boolValue(coordinate.id)) BigFraction.ONE
            else BigFraction.ZERO)
        SourceCoordinate.Kind.Integer -> arithmetic.rational(BigFraction.of(exactIntValue(coordinate.id), BIG_ONE))
        SourceCoordinate.Kind.Real -> algebraicReals?.get(coordinate.id)
            ?: exactReals?.get(coordinate.id)?.let(arithmetic::rational)
    }
