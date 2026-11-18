package com.eignex.klause.lp.relaxation

import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.LpRowSlack
import com.eignex.klause.lp.engine.UNPRICED_CUT_SLACK

internal fun Cut.sourceRowSlack(problem: Problem): LpRowSlack? =
    if (problem.semantics.relaxesRows(problem.numRealVars)) slack ?: UNPRICED_CUT_SLACK else null
