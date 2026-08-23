package com.eignex.klause.solver

import com.eignex.klause.arithmetic.difference.supportsCompleteDifferenceTheory
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.lp.smallModelBigIntBound

/** The solver pipeline selected once from a source [ProblemSpec]. */
enum class ProblemPipeline {
    /** Every integer is finitely bounded, so the ordinary CP pipeline applies. */
    FINITE_CP,

    /** Open integer sides are covered entirely by difference logic. */
    DIFFERENCE_THEORY,

    /** Open integer sides are covered by the complete finite-witness General LIA procedure. */
    GENERAL_LIA,

    /** Open pure-real linear arithmetic, decided by the exact rational simplex under Boolean search. */
    EXACT_LRA,

    /** Open mixed integer/real linear arithmetic, decided by exact rational LP and integer branching. */
    EXACT_LIRA,

    /** An open integer side reaches a factor no available theory decides. */
    UNSUPPORTED_OPEN,
}

/** Select the only sound pipeline for this source model before CP domains are materialized. */
fun ProblemSpec.pipeline(): ProblemPipeline {
    if ((0 until numIntVars).all { intBounds.hasLower(it) && intBounds.hasUpper(it) }) {
        return ProblemPipeline.FINITE_CP
    }
    if (supportsExactLra()) return ProblemPipeline.EXACT_LRA
    if (supportsExactLira()) return ProblemPipeline.EXACT_LIRA
    if (numRealVars != 0) return ProblemPipeline.UNSUPPORTED_OPEN
    return if (supportsCompleteDifferenceTheory(factors, numIntVars, intBounds)) {
        ProblemPipeline.DIFFERENCE_THEORY
    } else if (generalLiaWitnessBound() != null) {
        ProblemPipeline.GENERAL_LIA
    } else {
        ProblemPipeline.UNSUPPORTED_OPEN
    }
}

/** Factors whose Boolean skeleton and rational rows the exact pure-real lane decides completely. */
internal fun ProblemSpec.supportsExactLra(): Boolean =
    numIntVars == 0 && numRealVars != 0 && factors.all(::supportsExactTheoryFactor)

/** Factors whose mixed rows the exact QF_LIRA branch-and-simplex route decides. */
internal fun ProblemSpec.supportsExactLira(): Boolean =
    numIntVars != 0 && numRealVars != 0 && factors.all(::supportsExactTheoryFactor)

private fun supportsExactTheoryFactor(factor: Factor): Boolean = when (factor) {
    is Clause -> true
    is Linear -> factor.wide || factor.coefficientsAreExactlyRepresentable()
    is ReifiedLinear -> factor.wide || factor.coefficientsAreExactlyRepresentable()
    is ReifiedRealLinear -> factor.coefficientsAreExactlyRepresentable()
    else -> false
}

private fun Linear.coefficientsAreExactlyRepresentable(): Boolean =
    realBound.isFinite() && realIntCoeffs.all(Double::isFinite) && realCoeffs.all(Double::isFinite) &&
        (!hasReals || realIntCoeffs.all(::isExactInteger)) &&
        (hasReals || (vars.indices.all { isExactInteger(coeff(it).toDouble()) } && isExactInteger(bound.toDouble())))

private fun ReifiedLinear.coefficientsAreExactlyRepresentable(): Boolean =
    vars.indices.all { isExactInteger(coeff(it).toDouble()) } && isExactInteger(bound.toDouble())

private fun ReifiedRealLinear.coefficientsAreExactlyRepresentable(): Boolean = bound.isFinite() && intCoeffs.all(
    Double::isFinite,
) && realCoeffs.all(Double::isFinite) && intCoeffs.all(::isExactInteger)

private fun isExactInteger(value: Double): Boolean = value.isFinite() && value == value.toLong().toDouble()

/**
 * A finite [com.ionspin.kotlin.bignum.integer.BigInteger] box which preserves satisfiability of this
 * open General LIA model, or null when a factor falls outside the exact integer fragment.
 *
 * The small-model theorem includes declared finite sides as rows, so the resulting box preserves them
 * without treating an implementation clamp as part of the model.
 */
internal fun ProblemSpec.generalLiaWitnessBound() =
    if (numRealVars == 0) smallModelBigIntBound(numIntVars, factors.asList(), intBounds) else null
