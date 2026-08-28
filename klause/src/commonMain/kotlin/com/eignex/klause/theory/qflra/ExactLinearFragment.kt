package com.eignex.klause.theory.qflra

import com.eignex.klause.ir.ProblemSpec

/** Whether the exact pure-real lane decides every factor in this source model. */
fun ProblemSpec.supportsExactLra(): Boolean =
    numIntVars == 0 && numRealVars != 0 && factors.all { it.exactTheoryOwnable }

/** Whether the exact mixed integer/real lane decides every factor in this source model. */
internal fun ProblemSpec.supportsExactLira(): Boolean =
    numIntVars != 0 && numRealVars != 0 && factors.all { it.exactTheoryOwnable }
