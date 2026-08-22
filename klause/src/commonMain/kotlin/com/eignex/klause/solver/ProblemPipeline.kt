package com.eignex.klause.solver

import com.eignex.klause.lp.smallModelBigIntBound
import com.eignex.klause.propagation.difference.supportsCompleteDifferenceTheory

/** The solver pipeline selected once from a source [ProblemSpec]. */
enum class ProblemPipeline {
    /** Every integer is finitely bounded, so the ordinary CP pipeline applies. */
    FINITE_CP,

    /** Open integer sides are covered entirely by difference logic. */
    DIFFERENCE_THEORY,

    /** Open integer sides are covered by the complete finite-witness General LIA procedure. */
    GENERAL_LIA,

    /** An open integer side reaches a factor no available theory decides. */
    UNSUPPORTED_OPEN,
}

/** Select the only sound pipeline for this source model before CP domains are materialized. */
fun ProblemSpec.pipeline(): ProblemPipeline {
    if ((0 until numIntVars).all { intBounds.hasLower(it) && intBounds.hasUpper(it) }) {
        return ProblemPipeline.FINITE_CP
    }
    if (numRealVars != 0) return ProblemPipeline.UNSUPPORTED_OPEN
    return if (supportsCompleteDifferenceTheory(factors, numIntVars, intBounds)) {
        ProblemPipeline.DIFFERENCE_THEORY
    } else if (generalLiaWitnessBound() != null) {
        ProblemPipeline.GENERAL_LIA
    } else {
        ProblemPipeline.UNSUPPORTED_OPEN
    }
}

/**
 * A finite [com.ionspin.kotlin.bignum.integer.BigInteger] box which preserves satisfiability of this
 * open General LIA model, or null when a factor falls outside the exact integer fragment.
 *
 * The small-model theorem includes declared finite sides as rows, so the resulting box preserves them
 * without treating an implementation clamp as part of the model.
 */
internal fun ProblemSpec.generalLiaWitnessBound() =
    if (numRealVars == 0) smallModelBigIntBound(numIntVars, factors.asList(), intBounds) else null
