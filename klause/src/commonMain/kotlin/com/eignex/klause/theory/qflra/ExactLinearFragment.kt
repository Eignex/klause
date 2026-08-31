package com.eignex.klause.theory.qflra

import com.eignex.klause.ir.Problem

/** Whether the exact pure-real lane decides every factor in this source model. */
fun Problem.supportsExactLra(): Boolean = numIntVars == 0 && numRealVars != 0 && factors.all { it.exactTheoryOwnable }

/** Whether the exact integer-containing linear lane decides every factor in this source model. */
internal fun Problem.supportsExactLira(): Boolean = numIntVars != 0 && factors.all { it.exactTheoryOwnable }
