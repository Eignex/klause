package com.eignex.klause.solver.evidence

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation

internal fun sameSourceClaimKind(first: SourceClaim, second: SourceClaim): Boolean = when (first) {
    is SourceClaim.Witness -> second is SourceClaim.Witness
    is SourceClaim.Bound -> second is SourceClaim.Bound
    is SourceClaim.Optimal -> second is SourceClaim.Optimal
    is SourceClaim.Limit -> second is SourceClaim.Limit
    is SourceClaim.Clause -> second is SourceClaim.Clause
    is SourceClaim.Ray -> second is SourceClaim.Ray
    is SourceClaim.Consequence -> second is SourceClaim.Consequence
    is SourceClaim.Extension -> second is SourceClaim.Extension
    SourceClaim.Refutation -> second == SourceClaim.Refutation
}

internal fun matchSourceClaims(
    first: SourceClaim, second: SourceClaim, cancellation: Cancellation,
): SourceEvidenceCheck = try {
    val algebraic = AlgebraicArithmetic(PolynomialArithmetic(cancellation))
    algebraic.arithmetic.step()
    val matches = when (first) {
        is SourceClaim.Witness -> second is SourceClaim.Witness && matchSample(first.sample, second.sample, algebraic)
        is SourceClaim.Bound -> second is SourceClaim.Bound && first.strict == second.strict &&
            matchValue(first.value, second.value, algebraic)
        is SourceClaim.Optimal -> second is SourceClaim.Optimal &&
            matchSample(first.sample, second.sample, algebraic) &&
            matchValue(first.value, second.value, algebraic)
        is SourceClaim.Limit -> second is SourceClaim.Limit && matchSample(first.sample, second.sample, algebraic) &&
            matchValue(first.value, second.value, algebraic)
        is SourceClaim.Clause -> second is SourceClaim.Clause && first.literals == second.literals
        is SourceClaim.Ray -> second is SourceClaim.Ray && first.direction == second.direction &&
            matchSample(first.sample, second.sample, algebraic)
        is SourceClaim.Consequence -> second is SourceClaim.Consequence && first.inequality == second.inequality
        SourceClaim.Refutation -> second == SourceClaim.Refutation
        is SourceClaim.Extension -> first === second
    }
    algebraic.arithmetic.step()
    if (matches) SourceEvidenceCheck.Checked
    else SourceEvidenceCheck.Rejected("captured evidence does not match the actual source claim")
} catch (_: IncompleteClaim) {
    SourceEvidenceCheck.Unavailable("source claim lacks exact real coordinates")
} catch (_: PolynomialInterrupted) {
    SourceEvidenceCheck.Unavailable("source claim comparison cancelled")
} catch (_: PolynomialResourceLimit) {
    SourceEvidenceCheck.Unavailable("source claim comparison arithmetic allowance exhausted")
}

private fun matchValue(first: SourceValue, second: SourceValue, algebraic: AlgebraicArithmetic): Boolean {
    if (first is SourceValue.Extension || second is SourceValue.Extension) return first === second
    fun value(scalar: SourceValue): AlgebraicReal = when (scalar) {
        is SourceValue.Rational -> algebraic.rational(scalar.value)
        is SourceValue.Algebraic -> scalar.value
        is SourceValue.Extension -> error("symbolic values require their source checker")
    }
    return algebraic.compare(value(first), value(second)) == 0
}

private fun matchSample(first: Sample, second: Sample, algebraic: AlgebraicArithmetic): Boolean {
    if (first.numBoolVars != second.numBoolVars || first.numIntVars != second.numIntVars ||
        first.numRealVars != second.numRealVars
    ) return false
    for (id in 0 until first.numBoolVars) {
        algebraic.arithmetic.step()
        if (first.boolValue(id) != second.boolValue(id)) return false
    }
    for (id in 0 until first.numIntVars) {
        algebraic.arithmetic.step()
        if (first.exactIntValue(id) != second.exactIntValue(id)) return false
    }
    for (id in 0 until first.numRealVars) {
        val left = first.algebraicReals?.get(id)?.let { SourceValue.Algebraic(it) }
            ?: first.exactReals?.get(id)?.let { SourceValue.Rational(it) } ?: throw IncompleteClaim()
        val right = second.algebraicReals?.get(id)?.let { SourceValue.Algebraic(it) }
            ?: second.exactReals?.get(id)?.let { SourceValue.Rational(it) } ?: throw IncompleteClaim()
        if (!matchValue(left, right, algebraic)) return false
    }
    return true
}

private class IncompleteClaim : RuntimeException()
