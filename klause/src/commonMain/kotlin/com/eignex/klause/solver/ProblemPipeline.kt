package com.eignex.klause.solver

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.lp.smallModelBigIntBound

/** The solver pipeline selected once from a source [ProblemSpec]. */
enum class ProblemPipeline {
    /**
     * Finite search and optimization route.
     *
     * This is a frontend policy, not variable ownership: [ProblemSpec.componentPlan] may still select a
     * complete arithmetic theory for the same finite source model when no finite-domain factor is present.
     */
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

/**
 * The route a frontend hands to the solver for one source model.
 *
 * A fully bounded model stays on the finite route: that is a frontend policy about which engine owns a
 * finite domain, not a statement that no theory could decide it. Anything with an open integer side is
 * classified by [componentPlan], which reads column and factor ownership rather than demanding one
 * theory cover the whole model.
 */
fun ProblemSpec.sourceRoute(): ProblemPipeline = when {
    (0 until numIntVars).all { intBounds.hasLower(it) && intBounds.hasUpper(it) } -> ProblemPipeline.FINITE_CP

    // An open column a finite-domain factor reaches has no owner: CP cannot index it and the theory
    // cannot hold the factor. That is a verdict about the model, so it is answered here rather than
    // left to the plan, which states it as an invariant it may assume.
    !openColumnsAreTheoryEligible() -> ProblemPipeline.UNSUPPORTED_OPEN

    else -> componentPlan().theoryPipeline
}

private fun ProblemSpec.openColumnsAreTheoryEligible(): Boolean {
    val hasRealColumns = numRealVars != 0
    return (0 until numIntVars).none { v ->
        (!intBounds.hasLower(v) || !intBounds.hasUpper(v)) && columnMustBeCpOwned(v, hasRealColumns)
    }
}

/** True when some factor only CP can hold reads column [v], so no theory can be handed it. */
internal fun ProblemSpec.columnMustBeCpOwned(v: Int, hasRealColumns: Boolean): Boolean =
    factors.any { f -> !f.isTheoryOwnable(hasRealColumns) && v in f.variables.ints }

/** Factors whose Boolean skeleton and rational rows the exact pure-real lane decides completely. */
internal fun ProblemSpec.supportsExactLra(): Boolean =
    numIntVars == 0 && numRealVars != 0 && factors.all(::supportsExactTheoryFactor)

/** Factors whose mixed rows the exact QF_LIRA branch-and-simplex route decides. */
internal fun ProblemSpec.supportsExactLira(): Boolean =
    numIntVars != 0 && numRealVars != 0 && factors.all(::supportsExactTheoryFactor)

/**
 * A factor some lane other than CP can hold, so CP need not own the columns it reads.
 *
 * The complement of this is exactly the set of factors [componentPlan] leaves to CP, so column
 * ownership and factor ownership are decided by one rule rather than by two that can disagree.
 */
internal fun Factor.isTheoryOwnable(hasRealColumns: Boolean): Boolean =
    this is Clause || supportsIntegerTheory() || (hasRealColumns && supportsExactTheoryFactor(this))

internal fun supportsExactTheoryFactor(factor: Factor): Boolean = when (factor) {
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
