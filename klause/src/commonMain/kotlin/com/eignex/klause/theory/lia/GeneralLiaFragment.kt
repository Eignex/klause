package com.eignex.klause.theory.lia

import com.eignex.klause.ir.ProblemSpec
import com.eignex.klause.lp.admitsSmallModelBound
import com.eignex.klause.lp.smallModelBigIntBound
import com.eignex.klause.util.Cancellation

/**
 * Whether the General LIA lane admits this model.
 *
 * This asks only the admissibility half of the witness theorem: forming the bound itself is exponential
 * in the row count, and route selection never needs its value.
 */
internal fun ProblemSpec.admitsGeneralLia(): Boolean =
    numRealVars == 0 && admitsSmallModelBound(numIntVars, factors.asList(), intBounds)

/**
 * A finite [com.ionspin.kotlin.bignum.integer.BigInteger] box which preserves satisfiability of this
 * open General LIA model, or null when a factor falls outside the exact integer fragment.
 *
 * The small-model theorem includes declared finite sides as rows, so the resulting box preserves them
 * without treating an implementation clamp as part of the model.
 */
internal fun ProblemSpec.generalLiaWitnessBound(cancellation: Cancellation = Cancellation.Never) =
    if (numRealVars == 0) smallModelBigIntBound(numIntVars, factors.asList(), intBounds, cancellation) else null
