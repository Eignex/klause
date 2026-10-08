package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.linearRows

internal val Factor.integerTheoryOwnable: Boolean
    get() = intVars.isNotEmpty() && (linearForm is LinearForm.Conjunction || linearForm is LinearForm.Disjunction) &&
        linearRows.all { it.constants is IntegralConstants }
