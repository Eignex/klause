package com.eignex.klause.solver

import com.eignex.klause.propagation.difference.supportsCompleteDifferenceTheory

/** The solver pipeline selected once from a source [ProblemSpec]. */
enum class ProblemPipeline {
    /** Every integer is finitely bounded, so the ordinary CP pipeline applies. */
    FINITE_CP,

    /** Open integer sides are covered entirely by difference logic. */
    DIFFERENCE_THEORY,

    /** An open integer side reaches a factor no available theory decides. */
    UNSUPPORTED_OPEN,
}

/** Select the only sound pipeline for this source model before CP domains are materialized. */
fun ProblemSpec.pipeline(): ProblemPipeline {
    if ((0 until numIntVars).all { intBounds.hasLower(it) && intBounds.hasUpper(it) }) {
        return ProblemPipeline.FINITE_CP
    }
    return if (numRealVars == 0 && supportsCompleteDifferenceTheory(factors, numIntVars, intBounds)) {
        ProblemPipeline.DIFFERENCE_THEORY
    } else {
        ProblemPipeline.UNSUPPORTED_OPEN
    }
}
