package com.eignex.klause.theory.qflra

import com.eignex.klause.simplex.exact.BigFraction

/** An exact QF_LRA assignment, independent of the finite CP [com.eignex.klause.solver.Sample]. */
data class ExactLraAssignment(
    /** Boolean values indexed by model Boolean variable id. */
    val bools: BooleanArray,
    /** Rational real values indexed by model real variable id. */
    val reals: List<BigFraction>,
)
